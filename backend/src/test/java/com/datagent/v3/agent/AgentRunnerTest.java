package com.datagent.v3.agent;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.datagent.v3.session.*;
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
        var runner = new AgentRunner(store, tools, agent, model, contexts, json);
        var events = new ArrayList<RunEvent>();
        runner.execute(store.beginRun(session, "东京"), events::add);
        assertThat(inputs.get(1)).extracting(Message::toolCallId).containsSequence("1", "2");
        assertThat(inputs.get(1).getLast().success()).isFalse();
        assertThat(inputs.get(2).getLast().content()).contains("10:00:00+09:00");
        assertThat(events).extracting(RunEvent::type).containsExactly("run.started", "tool.started", "tool.finished",
                "tool.finished", "tool.started", "tool.finished", "text.delta", "run.completed");
        assertThat(events.getLast().messageSeq()).isEqualTo(6);
        runner.execute(store.beginRun(session, "刚才"), events::add);
        assertThat(inputs.getLast()).extracting(Message::content).contains("东京 10 点", "刚才");
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
        var runner = new AgentRunner(store, tools, agent, model, smallWindow, json);
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
                summaryNo.incrementAndGet();
                assertThat(request.messages().getFirst().content()).contains("inRun", "large-1");
                return new ModelReply("已完成前面的时间查询，继续使用结果", List.of());
            }
            return switch (callNo.incrementAndGet()) {
                case 1, 2 -> new ModelReply("", List.of(new Message.ToolCall("large-" + callNo.get(),
                        "current_time", "{}" + " ".repeat(2450))));
                default -> {
                    assertThat(request.messages()).extracting(Message::content).contains(original)
                            .anyMatch(text -> text.contains("本轮已完成工作"));
                    assertThat(request.messages()).extracting(Message::toolCallId).doesNotContain("large-1");
                    yield new ModelReply("处理完成", List.of());
                }
            };
        };
        var events = new ArrayList<RunEvent>();
        new AgentRunner(store, tools, agent, model, smallWindow, json)
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
        new AgentRunner(store, tools, agent, model, contexts, json)
                .execute(store.beginRun(session, "读回原文"), events::add);
        assertThat(events.getLast().type()).isEqualTo("run.completed");
    }

    @Test void stopsOnEighthRequest() {
        var session = store.createSession(agent.key());
        var count = new AtomicInteger();
        var runner = new AgentRunner(store, tools, agent, (request, delta) ->
                new ModelReply("", List.of(new Message.ToolCall("t" + count.incrementAndGet(), "current_time", "{}"))), contexts, json);
        var events = new ArrayList<RunEvent>();
        runner.execute(store.beginRun(session, "loop"), events::add);
        assertThat(count.get()).isEqualTo(8);
        assertThat(events.stream().filter(e -> "tool.started".equals(e.type())).count()).isEqualTo(7);
        assertThat(events.stream().filter(e -> "run.failed".equals(e.type())).count()).isEqualTo(1);
        assertThat(store.snapshot(session).activeRunId()).isNull();
    }

    @Test void interruptedStreamPreservesPartialTextAndReleasesSession() {
        var session = store.createSession(agent.key());
        var runner = new AgentRunner(store, tools, agent, (request, delta) -> {
            delta.accept("未完成");
            throw new IllegalStateException("连接中断");
        }, contexts, json);
        var events = new ArrayList<RunEvent>();
        runner.execute(store.beginRun(session, "hi"), events::add);
        assertThat(events.getLast().type()).isEqualTo("run.failed");
        assertThat(store.snapshot(session).runs().getLast().messages().getLast().content()).isEqualTo("未完成");
        new AgentRunner(store, tools, agent, (request, delta) -> new ModelReply(" ", List.of()), contexts, json)
                .execute(store.beginRun(session, "empty"), events::add);
        assertThat(store.snapshot(session).runs().getLast().status()).isEqualTo("FAILED");
    }
}
