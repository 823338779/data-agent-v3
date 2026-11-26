package com.datagent.v3.agent;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.datagent.v3.session.*;
import com.datagent.v3.memory.*;
import com.datagent.v3.tool.*;
import com.datagent.v3.model.*;
import com.datagent.v3.model.ModelClient.ModelReply;

@SpringBootTest
class AgentRunnerTest extends PostgresTest {
    @Autowired SessionStore store;
    @Autowired AgentDefinition agent;
    @Autowired ToolRegistry tools;
    @Autowired ConversationContextService contexts;
    @Autowired tools.jackson.databind.ObjectMapper json;

    @Autowired MemoryRequestAssembly memories;
    @Autowired MemoryMutationService memoryMutations;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test void explicitMemoryPersistsAcrossSessionsWithoutAnotherWriteTool() {
        jdbc.update("DELETE FROM agent_memory WHERE agent_key = ?", agent.key());
        String first = store.createSession(agent.key());
        var calls = new AtomicInteger();
        List<ModelClient.ModelRequest> requests = new ArrayList<>();
        var runner = new AgentRunner(store, tools, agent, (request, delta) -> {
            requests.add(request);
            if (calls.getAndIncrement() == 0) {
                return new ModelReply("", List.of(new Message.ToolCall("save", "add_memory",
                        "{\"content\":\"经营周报默认用万元\"}")));
            }
            return new ModelReply("完成", List.of());
        }, contexts, json, memories, memoryMutations);
        var events = new ArrayList<RunEvent>();
        runner.execute(store.beginRun(first, "以后经营周报默认用万元"), events::add);
        runner.execute(store.beginRun(store.createSession(agent.key()), "生成经营周报"), events::add);
        assertThat(requests.getFirst().tools().toString()).contains("add_memory");
        assertThat(requests.getLast().tools().toString()).doesNotContain("add_memory", "edit_memory");
        assertThat(requests.getLast().system()).contains("经营周报默认用万元");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_memory WHERE agent_key = ?", Integer.class, agent.key()))
                .isEqualTo(1);
        assertThat(store.snapshot(first).runs().getFirst().messages()).filteredOn(row -> "save".equals(row.toolCallId()))
                .singleElement().satisfies(row -> assertThat(row.success()).isTrue());
        jdbc.update("DELETE FROM agent_memory WHERE agent_key = ?", agent.key());
    }

    @Test void crossSessionUpdateIsVisibleOnNextBusinessCallAndWritesRequireAuthorization() {
        var key = "memory-refresh-" + UUID.randomUUID();
        String session = store.createSession(key);
        jdbc.update("INSERT INTO agent_memory(agent_key,content,revision) VALUES (?,?,1)", key, "周报默认用万元");
        var calls = new AtomicInteger();
        var events = new ArrayList<RunEvent>();
        ModelClient model = (input, delta) -> {
            assertThat(input.tools().toString()).doesNotContain("add_memory", "edit_memory");
            if (calls.getAndIncrement() == 0) {
                assertThat(input.system()).contains("周报默认用万元");
                jdbc.update("UPDATE agent_memory SET content = ?, revision = 2 WHERE agent_key = ?", "周报默认用元", key);
                return new ModelReply("", List.of(new Message.ToolCall("rejected", "add_memory",
                        "{\"content\":\"周报默认用万元\"}")));
            }
            assertThat(input.system()).contains("周报默认用元").doesNotContain("周报默认用万元");
            assertThat(input.messages().getLast().success()).isFalse();
            assertThat(input.messages().getLast().content()).contains("NOT_AUTHORIZED");
            return new ModelReply("完成", List.of());
        };
        try {
            new AgentRunner(store, tools, agent, model, contexts, json, memories, memoryMutations)
                    .execute(store.beginRun(session, "这次周报用元"), events::add);
            assertThat(events.getLast().type()).isEqualTo(RunEvent.COMPLETED);
            assertThat(jdbc.queryForObject("SELECT revision FROM agent_memory WHERE agent_key = ?", Long.class, key)).isEqualTo(2);
        } finally {
            jdbc.update("DELETE FROM agent_memory WHERE agent_key = ?", key);
        }
    }

    @Test void committedMemoryRemainsWhenAnswerFailsAndExplicitForgetClearsBody() {
        var key = "memory-forget-" + UUID.randomUUID();
        String session = store.createSession(key);
        String preference = "经营周报默认用万元";
        var calls = new AtomicInteger();
        var events = new ArrayList<RunEvent>();
        ModelClient model = (input, delta) -> switch (calls.getAndIncrement()) {
            case 0 -> new ModelReply("", List.of(new Message.ToolCall("same-id", "add_memory",
                    json.writeValueAsString(Map.of("content", preference)))));
            case 1 -> throw new IllegalStateException("模型断流");
            case 2 -> new ModelReply("", List.of(new Message.ToolCall("same-id", "edit_memory",
                    json.writeValueAsString(Map.of("old_string", preference, "new_string", "")))));
            default -> {
                assertThat(input.system()).contains("<memory>\n\"\"\n</memory>");
                yield new ModelReply("已遗忘", List.of());
            }
        };
        var runner = new AgentRunner(store, tools, agent, model, contexts, json, memories, memoryMutations);
        try {
            runner.execute(store.beginRun(session, "以后" + preference), events::add);
            assertThat(events.getLast().type()).isEqualTo(RunEvent.FAILED);
            assertThat(store.snapshot(session).runs().getFirst().messages()).filteredOn(row -> "same-id".equals(row.toolCallId()))
                    .singleElement().satisfies(row -> assertThat(row.success()).isTrue());
            assertThat(jdbc.queryForObject("SELECT content FROM agent_memory WHERE agent_key = ?", String.class, key)).isEqualTo(preference);
            runner.execute(store.beginRun(session, "忘记「" + preference + "」"), events::add);
            assertThat(events.getLast().type()).isEqualTo(RunEvent.COMPLETED);
            assertThat(jdbc.queryForObject("SELECT revision FROM agent_memory WHERE agent_key = ?", Long.class, key)).isEqualTo(2);
        } finally {
            jdbc.update("DELETE FROM agent_memory WHERE agent_key = ?", key);
        }
    }

    @Test void readOnceThenIgnoreExcludesTheReadResultFromModelButKeepsOriginalHistory() {
        var key = "memory-read-" + UUID.randomUUID();
        String session = store.createSession(key);
        String run = store.beginRun(session, "你记住了什么？");
        var sentinel = "读取曾经的偏好原文";
        jdbc.update("INSERT INTO agent_memory(agent_key,content,revision) VALUES (?,?,1)", key, sentinel);
        var calls = new AtomicInteger();
        var events = new ArrayList<RunEvent>();
        ModelClient model = (input, delta) -> {
            if (calls.getAndIncrement() == 0) {
                assertThat(input.tools().toString()).contains("readMemory").doesNotContain("add_memory", "edit_memory");
                store.acceptSteer(session, run, UUID.randomUUID(), "这次忽略记忆");
                return new ModelReply("", List.of(new Message.ToolCall("read", "readMemory", "{}")));
            }
            assertThat(input.tools().toString()).doesNotContain("readMemory", "add_memory", "edit_memory");
            assertThat(input.system()).doesNotContain(sentinel);
            assertThat(input.messages()).extracting(Message::content).noneMatch(text -> text.contains(sentinel));
            return new ModelReply("本轮忽略记忆", List.of());
        };
        try {
            new AgentRunner(store, tools, agent, model, contexts, json, memories, memoryMutations).execute(run, events::add);
            assertThat(events.getLast().type()).isEqualTo(RunEvent.COMPLETED);
            assertThat(store.snapshot(session).runs().getFirst().messages()).filteredOn(row -> "read".equals(row.toolCallId()))
                    .singleElement().satisfies(row -> {
                        assertThat(row.content()).contains(sentinel);
                        assertThat(row.success()).isTrue();
                    });
            assertThat(jdbc.queryForObject("SELECT content FROM agent_memory WHERE agent_key = ?", String.class, key)).isEqualTo(sentinel);
        } finally {
            jdbc.update("DELETE FROM agent_memory WHERE agent_key = ?", key);
        }
    }

    @Test void multipleCallsAndSelfCorrectionKeepOriginalIds() {
        var step = new AtomicInteger();
        List<List<Message>> inputs = new ArrayList<>();
        ModelClient model = (request, onText) -> {
            inputs.add(List.copyOf(request.messages()));
            return switch (step.incrementAndGet()) {
                case 1 -> new ModelReply("", List.of(new Message.ToolCall("1", "current_time", "{}"),
                        new Message.ToolCall("2", "convert_timezone", "{}")));
                case 2 -> new ModelReply("", List.of(new Message.ToolCall("3", "convert_timezone",
                        "{\"timestamp\":\"2026-11-26T01:00:00Z\",\"timezone\":\"Asia/Tokyo\"}")));
                default -> { onText.accept("东京 10 点"); yield new ModelReply("东京 10 点", List.of()); }
            };
        };
        var session = store.createSession(agent.key());
        var runner = new AgentRunner(store, tools, agent, model, contexts, json, memories, memoryMutations);
        var events = new ArrayList<RunEvent>();
        runner.execute(store.beginRun(session, "东京"), events::add);
        assertThat(inputs.get(1)).extracting(Message::toolCallId).containsSequence("1", "2");
        assertThat(inputs.get(1).getLast().success()).isFalse();
        assertThat(inputs.get(2).getLast().content()).contains("10:00:00+09:00");
        assertThat(events).extracting(RunEvent::type).containsExactly("run.started", "tool.started", "tool.finished",
                "tool.finished", "tool.started", "tool.finished", "text.delta", "run.completed");
        assertThat(events.getLast().messageSeq()).isEqualTo(6);
        runner.execute(store.beginRun(session, "刚才"), events::add);
        assertThat(inputs.getLast()).extracting(Message::content).contains("东京 10 点", "[本轮任务] 刚才");
    }

    @Test void longConversationCompactsAndNextRunReadsPersistedCheckpoint() {
        var session = store.createSession(agent.key());
        for (int i = 1; i <= 12; i++) {
            var run = store.beginRun(session, i == 1 ? "只统计已支付订单" : "q".repeat(360));
            store.finish(run, Message.assistant("a".repeat(360), List.of()));
        }
        var smallWindow = new ConversationContextService(json, 7000, 512, 256);
        var model = (ModelClient) (request, delta) -> {
            if (request.tools().isEmpty()) {
                assertThat(request.maxOutputTokens()).isEqualTo(256);
                return new ModelReply("只统计已支付订单", List.of());
            }
            assertThat(request.maxOutputTokens()).isEqualTo(512);
            assertThat(request.messages()).extracting(Message::content)
                    .anyMatch(text -> text.contains("会话历史摘要") && text.contains("已支付"));
            return new ModelReply("按地区查询已支付订单", List.of());
        };
        var runner = new AgentRunner(store, tools, agent, model, smallWindow, json, memories, memoryMutations);
        var events = new ArrayList<RunEvent>();
        runner.execute(store.beginRun(session, "按地区拆分"), events::add);
        assertThat(events.getLast().type()).isEqualTo("run.completed");
        assertThat(store.snapshot(session).runs().getFirst().messages().getFirst().content()).isEqualTo("只统计已支付订单");
        var next = store.beginRun(session, "继续");
        assertThat(store.context(next).checkpoint().summary()).contains("已支付");
        runner.execute(next, events::add);
        assertThat(events.getLast().type()).isEqualTo("run.completed");
    }

    @Test void longToolChainCompactsInRunWithoutPersistingSyntheticMessages() {
        var session = store.createSession(agent.key());
        var smallWindow = new ConversationContextService(json, 6200, 512, 256);
        // 用空白构造较大的真实协议参数，当前工具仍只执行一次同样的只读动作。
        var callNo = new AtomicInteger();
        var summaryNo = new AtomicInteger();
        var original = "把查询结果按地区展示";
        ModelClient model = (request, delta) -> {
            if (request.tools().isEmpty()) {
                // 记忆的固定提示词占用预算，可能需要折叠两批；仍逐批核对真实来源。
                int summary = summaryNo.incrementAndGet();
                assertThat(request.messages().getFirst().content()).contains("inRun", "large-" + summary)
                        .doesNotContain("### Current memory");
                return new ModelReply("已完成前面的时间查询，继续使用结果", List.of());
            }
            return switch (callNo.incrementAndGet()) {
                case 1, 2 -> new ModelReply("", List.of(new Message.ToolCall("large-" + callNo.get(),
                        "current_time", "{}" + " ".repeat(2450))));
                default -> {
                    assertThat(request.messages()).extracting(Message::content).contains("[本轮任务] " + original)
                            .anyMatch(text -> text.contains("本轮已完成工作"));
                    assertThat(request.messages()).extracting(Message::toolCallId).doesNotContain("large-1");
                    yield new ModelReply("处理完成", List.of());
                }
            };
        };
        var events = new ArrayList<RunEvent>();
        new AgentRunner(store, tools, agent, model, smallWindow, json, memories, memoryMutations)
                .execute(store.beginRun(session, original), events::add);
        assertThat(events.getLast().type()).isEqualTo("run.completed");
        assertThat(events.stream().filter(e -> "tool.started".equals(e.type()))).hasSize(2);
        assertThat(summaryNo.get()).isPositive();
        assertThat(store.snapshot(session).runs().getLast().messages()).extracting(Message::toolCallId)
                .contains("large-1", "large-2");
        assertThat(store.snapshot(session).runs().getLast().messages()).extracting(Message::content)
                .noneMatch(text -> text.contains("本轮已完成工作"));
    }

    @Test void modelCanRecallOriginalUserTextWithRunBoundTool() {
        var session = store.createSession(agent.key());
        var historic = store.beginRun(session, "保留口径" + "字".repeat(1000));
        store.finish(historic, Message.assistant("确认", List.of()));
        var requests = new AtomicInteger();
        ModelClient model = (request, delta) -> {
            if (requests.getAndIncrement() == 0) {
                assertThat(request.tools()).anyMatch(tool -> tool.toString().contains("read_conversation_message"));
                return new ModelReply("", List.of(new Message.ToolCall("read", "read_conversation_message",
                        "{\"turn\":\"1\",\"seq\":\"0\",\"offset\":\"0\"}")));
            }
            assertThat(request.messages().getLast().toolCallId()).isEqualTo("read");
            assertThat(request.messages().getLast().content()).contains("保留口径", "nextOffset");
            return new ModelReply("已回查", List.of());
        };
        var events = new ArrayList<RunEvent>();
        new AgentRunner(store, tools, agent, model, contexts, json, memories, memoryMutations)
                .execute(store.beginRun(session, "读回原文"), events::add);
        assertThat(events.getLast().type()).isEqualTo("run.completed");
    }

    @Test void steersOnlyBetweenCompleteToolBatches() {
        var session = store.createSession(agent.key());
        var run = store.beginRun(session, "首问");
        var calls = new AtomicInteger();
        var events = new ArrayList<RunEvent>();
        var first = new Message.ToolCall("a", "current_time", "{}");
        var second = new Message.ToolCall("b", "current_time", "{}");
        ModelClient fake = (request, onText) -> {
            if (calls.incrementAndGet() == 1) return new ModelReply("", List.of(first, second));
            assertThat(request.messages()).extracting(Message::role).containsExactly("user", "assistant", "tool", "tool", "user", "user");
            assertThat(request.messages().subList(2, 4)).extracting(Message::toolCallId).containsExactly("a", "b");
            assertThat(request.messages()).extracting(Message::content).endsWith("[本轮补充] 补一", "[本轮补充] 补二");
            return new ModelReply("完成", List.of());
        };
        new AgentRunner(store, tools, agent, fake, contexts, json, memories, memoryMutations).execute(run, event -> {
            events.add(event);
            // 第一个工具已经开始，第二个还没完成；测试其间补充不得伪装成批内消息。
            if (RunEvent.TOOL_STARTED.equals(event.type()) && "a".equals(event.call().id())) {
                store.acceptSteer(session, run, UUID.randomUUID(), "补一");
                store.acceptSteer(session, run, UUID.randomUUID(), "补二");
            }
        });
        assertThat(events.stream().filter(e -> RunEvent.TOOL_STARTED.equals(e.type())))
                .extracting(e -> e.call().id()).containsExactly("a", "b");
        assertThat(events.getLast().type()).isEqualTo(RunEvent.COMPLETED);
        var transcript = store.snapshot(session).runs().getFirst();
        assertThat(transcript.steers()).extracting(SessionStore.SteerReceipt::messageSeq).containsExactly(4, 5);
    }

    @Test void failsAfterCommittedReplyWithoutDuplicatingPartialOrCheckpoint() {
        var session = store.createSession(agent.key());
        var old = store.beginRun(session, "旧口径");
        store.finish(old, Message.assistant("旧回答", List.of()));
        var saved = store.beginRun(session, "已总结");
        var checkpoint = new SessionStore.Checkpoint(1, "已确认旧口径");
        store.finishWithContext(saved, Message.assistant("已保存", List.of()), checkpoint);
        var run = store.beginRun(session, "首问");
        var calls = new AtomicInteger();
        ModelClient failure = (request, onText) -> {
            if (calls.incrementAndGet() == 1) {
                onText.accept("已显示回答");
                store.acceptSteer(session, run, UUID.randomUUID(), "补充原文");
                return new ModelReply("已显示回答", List.of());
            }
            throw new IllegalStateException("模型不可用");
        };
        var events = new ArrayList<RunEvent>();
        new AgentRunner(store, tools, agent, failure, contexts, json, memories, memoryMutations).execute(run, events::add);
        assertThat(events.getLast().type()).isEqualTo(RunEvent.FAILED);
        var failed = store.snapshot(session).runs().getLast();
        assertThat(failed.messages()).extracting(Message::content).containsExactly("首问", "已显示回答", "补充原文");
        assertThat(failed.steers().getFirst().messageSeq()).isEqualTo(2);
        var next = store.beginRun(session, "下一轮");
        assertThat(store.context(next).checkpoint()).isEqualTo(checkpoint);
        assertThat(store.context(next).history()).extracting(SessionStore.RunView::id).doesNotContain(run);
        store.fail(next, "结束");
    }

    @Test void stopsOnEighthRequest() {
        var session = store.createSession(agent.key());
        var count = new AtomicInteger();
        var runner = new AgentRunner(store, tools, agent, (request, delta) ->
                new ModelReply("", List.of(new Message.ToolCall("t" + count.incrementAndGet(), "current_time", "{}"))), contexts, json, memories, memoryMutations);
        var events = new ArrayList<RunEvent>();
        runner.execute(store.beginRun(session, "loop"), events::add);
        assertThat(count.get()).isEqualTo(8);
        assertThat(events).filteredOn(e -> RunEvent.STEER_GATE.equals(e.type()))
                .singleElement().satisfies(e -> assertThat(e.steerCloseReason()).isEqualTo(SessionStore.LIMIT));
        assertThat(events.stream().filter(e -> "tool.started".equals(e.type())).count()).isEqualTo(7);
        assertThat(events.stream().filter(e -> "run.failed".equals(e.type())).count()).isEqualTo(1);
        assertThat(store.snapshot(session).activeRunId()).isNull();
    }

    @Test void interruptedStreamPreservesPartialTextAndReleasesSession() {
        var session = store.createSession(agent.key());
        var runner = new AgentRunner(store, tools, agent, (request, delta) -> {
            delta.accept("未完成");
            throw new IllegalStateException("连接中断");
        }, contexts, json, memories, memoryMutations);
        var events = new ArrayList<RunEvent>();
        runner.execute(store.beginRun(session, "hi"), events::add);
        assertThat(events.getLast().type()).isEqualTo("run.failed");
        assertThat(store.snapshot(session).runs().getLast().messages().getLast().content()).isEqualTo("未完成");
        new AgentRunner(store, tools, agent, (request, delta) -> new ModelReply(" ", List.of()), contexts, json, memories, memoryMutations)
                .execute(store.beginRun(session, "empty"), events::add);
        assertThat(store.snapshot(session).runs().getLast().status()).isEqualTo("FAILED");
    }
}
