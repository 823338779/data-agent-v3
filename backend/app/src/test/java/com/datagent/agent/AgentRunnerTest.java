package com.datagent.agent;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.datagent.persistence.SessionStore;
import com.datagent.tool.execution.ToolExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.datagent.session.*;
import com.datagent.memory.*;
import com.datagent.tool.*;
import com.datagent.model.*;
import com.datagent.model.ModelClient.ModelReply;
import tools.jackson.databind.JsonNode;

@SpringBootTest
class AgentRunnerTest extends PostgresTest {
    @Autowired
    SessionStore store;
    @Autowired AgentDefinition agent;
    @Autowired ToolRegistry tools;
    @Autowired
    ToolExecution toolExecution;
    @Autowired ConversationContextService contexts;
    @Autowired MemoryRequestAssembly memories;
    @Autowired tools.jackson.databind.ObjectMapper json;

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static final String RUN_USAGE = "SELECT * FROM agent_run_usage WHERE run_id = ?";
    private static final String VIEW_MEMORY = "你记住了什么？", ORDINARY = "生成周报";
    private static final String REMOVED_ARCHIVE_TOOL = "read_archive";
    private static final String LEGACY_ARCHIVE = "[DATUS_ARCHIVED] path=pg:";
    private static final String READ_ARGUMENTS = "{\"action\":\"read\"}";
    private static final String MISSING_ACTION = "缺少必填参数：[action]";
    private static final String MISSING_EDIT_FIELDS = "缺少必填参数：[new_string, old_string]";
    private static final String MISSING_TIME_FIELDS = "缺少必填参数：[timestamp, timezone]";

    @Test
    @org.springframework.transaction.annotation.Transactional
    void springToolsUseOnlyTheCallingRunsContext(@Autowired List<AgentTool> beans) {
        // 验证真实容器发现的全部模型工具，注册表在两个运行中共享同一批 Bean。
        assertThat(beans).extracting(AgentTool::name).containsExactlyInAnyOrder(CurrentTimeTool.NAME,
                ConvertTimezoneTool.NAME, MemoryTool.NAME, LoadSkillTool.NAME);
        assertThat(beans).allMatch(AgentTool::includeResultInHistory);
        String firstOwner = UUID.randomUUID().toString();
        String secondOwner = UUID.randomUUID().toString();
        String first = store.beginRun(store.createSession(firstOwner), VIEW_MEMORY);
        String second = store.beginRun(store.createSession(secondOwner), ORDINARY);
        jdbc.update("INSERT INTO agent_memory(agent_key,content,revision) VALUES (?,?,1)", firstOwner, VIEW_MEMORY);
        jdbc.update("INSERT INTO agent_memory(agent_key,content,revision) VALUES (?,?,1)", secondOwner, ORDINARY);
        List<Message> messages = List.of();
        Message.ToolCall read = new Message.ToolCall(firstOwner, MemoryTool.NAME, READ_ARGUMENTS);

        // 相同工具输入随各自运行读取不同的 Agent 记忆。
        Message assistant = Message.assistant("", List.of(read));
        int firstSeq = store.append(first, assistant);
        int secondSeq = store.append(second, assistant);
        assertThat(tools.find(MemoryTool.NAME).parameters()).doesNotContainKey("revision");
        assertThat(toolExecution.invoke(first, firstSeq, read, messages, 1, () -> {}).content()).contains(VIEW_MEMORY).doesNotContain("revision");
        assertThat(toolExecution.invoke(second, secondSeq, read, messages, 1, () -> {}).content()).contains(ORDINARY);
        assertThat(tools.invoke(read, AgentTool.Context.EMPTY, messages,
                () -> fail("缺少记忆读写入口时不应启动")).success()).isFalse();
        List<Map<String, String>> invalid = List.of(Map.of(MemoryTool.ACTION, MemoryTool.READ, MemoryTool.CONTENT, ORDINARY),
                Map.of(), Map.of(MemoryTool.ACTION, MemoryTool.EDIT));
        for (Map<String, String> arguments : invalid) {
            Message.ToolCall denied = new Message.ToolCall(firstOwner, MemoryTool.NAME, json.writeValueAsString(arguments));
            int seq = store.append(first, Message.assistant("", List.of(denied)));
            Message rejected = toolExecution.invoke(first, seq, denied, messages, 1, () -> fail("无效操作不应启动"));
            assertThat(rejected.success()).isFalse();
            // 通用必填参数与操作专属参数均应出现在对应的失败回执中。
            if (arguments.isEmpty()) assertThat(rejected.content()).contains(MISSING_ACTION);
            if (MemoryTool.EDIT.equals(arguments.get(MemoryTool.ACTION))) assertThat(rejected.content()).contains(MISSING_EDIT_FIELDS);
        }

        // 已结束的运行可以重放既有回执；尚无回执的新调用被运行时拒绝。
        int pendingSeq = store.append(first, assistant);
        store.fail(first, ORDINARY);
        assertThat(toolExecution.invoke(first, firstSeq, read, messages, 1,
                () -> fail("重放不应启动工具")).success()).isTrue();
        assertThatThrownBy(() -> toolExecution.invoke(first, pendingSeq, read, messages, 1, () -> fail("已结束的运行不应启动工具")))
                .hasMessageContaining("运行已结束");
    }

    @Test void recordsFailedBusinessAttemptWithoutInventingTokenCounts() {
        // 运行进入模型之后抛错，依然应记录一次业务调用，两个 token 维度均未知。
        String session = store.createSession(agent.key());
        String run = store.beginRun(session, "模拟断流");
        ModelClient broken = (request, delta) -> { throw new IllegalStateException("模型断流"); };
        new AgentRunner(store, tools, toolExecution, agent, broken, contexts, memories)
                .execute(run, event -> {});
        assertThat(jdbc.queryForMap(RUN_USAGE, run)).containsEntry("business_calls", 1L)
                .containsEntry("summary_calls", 0L).containsEntry("input_tokens", null)
                .containsEntry("output_tokens", null).containsEntry("input_reported_calls", 0L);
    }

    @Test void explicitMemoryPersistsAcrossSessionsWithTheSameTools() {
        jdbc.update("DELETE FROM agent_memory WHERE agent_key = ?", agent.key());
        String first = store.createSession(agent.key());
        AtomicInteger calls = new AtomicInteger();
        List<ModelClient.ModelRequest> requests = new ArrayList<>();
        AgentRunner runner = new AgentRunner(store, tools, toolExecution, agent, (request, delta) -> {
            requests.add(request);
            if (calls.getAndIncrement() == 0) {
                return new ModelReply("", List.of(new Message.ToolCall("save", MemoryTool.NAME,
                        "{\"action\":\"add\",\"content\":\"经营周报默认用万元\"}")));
            }
            return new ModelReply("完成", List.of());
        }, contexts, memories);
        ArrayList<RunEvent> events = new ArrayList<>();
        runner.execute(store.beginRun(first, "以后经营周报默认用万元"), events::add);
        runner.execute(store.beginRun(store.createSession(agent.key()), "生成经营周报"), events::add);
        assertThat(requests.getFirst().tools().toString()).contains(MemoryTool.NAME);
        assertThat(requests.getLast().tools()).isEqualTo(tools.definitions());
        assertThat(requests.getLast().system()).contains("经营周报默认用万元");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_memory WHERE agent_key = ?", Integer.class, agent.key()))
                .isEqualTo(1);
        assertThat(store.snapshot(first).runs().getFirst().messages()).filteredOn(row -> "save".equals(row.toolCallId()))
                .singleElement().satisfies(row -> assertThat(row.success()).isTrue());
        jdbc.update("DELETE FROM agent_memory WHERE agent_key = ?", agent.key());
    }

    @Test void crossSessionUpdateIsVisibleOnNextBusinessCallAndAddUsesLatestSnapshot() {
        String key = "memory-refresh-" + UUID.randomUUID();
        String session = store.createSession(key);
        jdbc.update("INSERT INTO agent_memory(agent_key,content,revision) VALUES (?,?,1)", key, "周报默认用万元");
        AtomicInteger calls = new AtomicInteger();
        ArrayList<RunEvent> events = new ArrayList<>();
        ModelClient model = (input, delta) -> {
            assertThat(input.tools()).isEqualTo(tools.definitions());
            if (calls.getAndIncrement() == 0) {
                assertThat(input.system()).contains("周报默认用万元");
                jdbc.update("UPDATE agent_memory SET content = ?, revision = 2 WHERE agent_key = ?", "周报默认用元", key);
                return new ModelReply("", List.of(new Message.ToolCall("add", MemoryTool.NAME,
                        "{\"action\":\"add\",\"content\":\"日报默认用元\"}")));
            }
            assertThat(input.system()).contains("周报默认用元").doesNotContain("周报默认用万元");
            assertThat(input.messages().getLast().success()).isTrue();
            assertThat(input.messages().getLast().content()).contains("added");
            assertThat(input.system()).contains("日报默认用元");
            return new ModelReply("完成", List.of());
        };
        try {
            new AgentRunner(store, tools, toolExecution, agent, model, contexts, memories)
                    .execute(store.beginRun(session, "这次周报用元"), events::add);
            assertThat(events.getLast().type()).isEqualTo(RunEvent.COMPLETED);
            assertThat(jdbc.queryForObject("SELECT revision FROM agent_memory WHERE agent_key = ?", Long.class, key)).isEqualTo(3);
        } finally {
            jdbc.update("DELETE FROM agent_memory WHERE agent_key = ?", key);
        }
    }

    @Test void committedMemoryRemainsWhenAnswerFailsAndExplicitForgetClearsBody() {
        String key = "memory-forget-" + UUID.randomUUID();
        String session = store.createSession(key);
        String preference = "经营周报默认用万元";
        AtomicInteger calls = new AtomicInteger();
        ArrayList<RunEvent> events = new ArrayList<>();
        ModelClient model = (input, delta) -> switch (calls.getAndIncrement()) {
            case 0 -> new ModelReply("", List.of(new Message.ToolCall("same-id", MemoryTool.NAME,
                    json.writeValueAsString(Map.of(MemoryTool.ACTION, MemoryTool.ADD, MemoryTool.CONTENT, preference)))));
            case 1 -> throw new IllegalStateException("模型断流");
            case 2 -> new ModelReply("", List.of(new Message.ToolCall("same-id", MemoryTool.NAME,
                    json.writeValueAsString(Map.of(MemoryTool.ACTION, MemoryTool.EDIT,
                            MemoryTool.OLD, preference, MemoryTool.NEW, "")))));
            default -> {
                assertThat(input.system()).contains("<memory>\n\"\"\n</memory>");
                yield new ModelReply("已遗忘", List.of());
            }
        };
        AgentRunner runner = new AgentRunner(store, tools, toolExecution, agent, model, contexts, memories);
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

    @Test void ignoreSteerReachesModelAlongsideSnapshotAndCurrentReadResult() {
        String key = "memory-read-" + UUID.randomUUID();
        String session = store.createSession(key);
        String run = store.beginRun(session, "你记住了什么？");
        String sentinel = "读取曾经的偏好原文";
        jdbc.update("INSERT INTO agent_memory(agent_key,content,revision) VALUES (?,?,1)", key, sentinel);
        AtomicInteger calls = new AtomicInteger();
        ArrayList<RunEvent> events = new ArrayList<>();
        ModelClient model = (input, delta) -> {
            if (calls.getAndIncrement() == 0) {
                assertThat(input.tools().toString()).contains(MemoryTool.NAME);
                store.acceptSteer(session, run, UUID.randomUUID(), "这次忽略记忆");
                return new ModelReply("", List.of(new Message.ToolCall("read", MemoryTool.NAME, READ_ARGUMENTS)));
            }
            assertThat(input.tools()).isEqualTo(tools.definitions());
            assertThat(input.system()).contains(sentinel);
            assertThat(input.messages()).filteredOn(row -> "read".equals(row.toolCallId()))
                    .singleElement().satisfies(row -> assertThat(row.content()).contains(sentinel));
            assertThat(input.messages().getLast().role()).isEqualTo(Message.USER);
            assertThat(input.messages().getLast().content()).contains("这次忽略记忆");
            return new ModelReply("本轮忽略记忆", List.of());
        };
        try {
            new AgentRunner(store, tools, toolExecution, agent, model, contexts, memories).execute(run, events::add);
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
        AtomicInteger step = new AtomicInteger();
        List<List<Message>> inputs = new ArrayList<>();
        var firstTrace = List.of(json.readTree("{\"type\":\"reasoning\",\"encrypted_content\":\"first-private-trace\"}"));
        var secondTrace = List.of(json.readTree("{\"type\":\"reasoning\",\"encrypted_content\":\"second-private-trace\"}"));
        List<Map<Integer, List<JsonNode>>> traces = new ArrayList<>();
        ModelClient model = (request, onText) -> {
            inputs.add(List.copyOf(request.messages()));
            traces.add(request.toolReasoning());
            return switch (step.incrementAndGet()) {
                case 1 -> new ModelReply("", List.of(new Message.ToolCall("1", "current_time", "{}"),
                        new Message.ToolCall("2", "convert_timezone", "{}")), firstTrace);
                case 2 -> new ModelReply("", List.of(new Message.ToolCall("3", "convert_timezone",
                        "{\"timestamp\":\"2026-11-26T01:00:00Z\",\"timezone\":\"Asia/Tokyo\"}")), secondTrace);
                default -> { onText.accept("东京 10 点"); yield new ModelReply("东京 10 点", List.of()); }
            };
        };
        String session = store.createSession(agent.key());
        AgentRunner runner = new AgentRunner(store, tools, toolExecution, agent, model, contexts, memories);
        ArrayList<RunEvent> events = new ArrayList<>();
        runner.execute(store.beginRun(session, "东京"), events::add);
        assertThat(inputs.get(1)).extracting(Message::toolCallId).containsSequence("1", "2");
        assertThat(inputs.get(1).getLast().success()).isFalse();
        assertThat(inputs.get(1).getLast().content()).contains(MISSING_TIME_FIELDS);
        assertThat(inputs.get(2).getLast().content()).contains("10:00:00+09:00");
        assertThat(events).extracting(RunEvent::type).containsExactly("run.started", "tool.started", "tool.finished",
                "tool.finished", "tool.started", "tool.finished", "text.delta", "run.completed");
        assertThat(events.getLast().messageSeq()).isEqualTo(6);
        runner.execute(store.beginRun(session, "刚才"), events::add);
        // 错误回执和后续修正都在同一工具链，下一轮、会话快照和事件不携带推理。
        assertThat(traces).containsExactly(Map.of(), Map.of(1, firstTrace),
                Map.of(1, firstTrace, 4, secondTrace), Map.of());
        assertThat(json.writeValueAsString(store.snapshot(session))).doesNotContain("private-trace");
        assertThat(json.writeValueAsString(events)).doesNotContain("private-trace");
        assertThat(inputs.getLast()).extracting(Message::content).contains("东京 10 点", "[本轮任务] 刚才");
    }

    @ParameterizedTest
    @ValueSource(strings = {"帮我梳理一下已有偏好", "/memory-organization 只看周报", "/memory-organize 只看周报"})
    void passesOriginalRequestAndLetsModelLoadTheSkill(String user) {
        String skill = "memory-organization";
        String session = store.createSession(agent.key());
        AtomicInteger steps = new AtomicInteger();
        List<RunEvent> events = new ArrayList<>();
        ModelClient model = (request, delta) -> {
            // 普通请求包含目录和工具定义，工作步骤应在成功的工具回执中才出现。
            assertThat(request.system()).containsSubsequence("## 当前任务", "## 工具调用", "## 可用技能",
                    "<available_skills>", skill, "</available_skills>", "## 长期记忆", "<memory>")
                    .doesNotContain("### Memory Organization");
            assertThat(request.tools()).anyMatch(schema -> LoadSkillTool.NAME.equals(schema.get("name")));
            return switch (steps.getAndIncrement()) {
                case 0 -> {
                    assertThat(request.messages().getLast().content()).isEqualTo("[本轮任务] " + user);
                    yield new ModelReply("", List.of(new Message.ToolCall("unknown", LoadSkillTool.NAME,
                            json.writeValueAsString(Map.of("skill_name", "../unlisted")))));
                }
                case 1 -> {
                    Message failure = request.messages().getLast();
                    assertThat(failure.success()).isFalse();
                    assertThat(failure.content()).contains("未知技能", "available_skills");
                    yield new ModelReply("", List.of(new Message.ToolCall("known", LoadSkillTool.NAME,
                            json.writeValueAsString(Map.of("skill_name", skill)))));
                }
                default -> {
                    Message receipt = request.messages().getLast();
                    assertThat(receipt.toolCallId()).isEqualTo("known");
                    assertThat(receipt.success()).isTrue();
                    assertThat(json.readTree(receipt.content()).path("content").asString())
                            .contains("### Memory Organization", "不执行修改或遗忘").doesNotContain("### Session Summarize");
                    yield new ModelReply("整理完成", List.of());
                }
            };
        };
        new AgentRunner(store, tools, toolExecution, agent, model, contexts, memories)
                .execute(store.beginRun(session, user), events::add);
        assertThat(events.getLast().type()).isEqualTo(RunEvent.COMPLETED);
        assertThat(steps.get()).isEqualTo(3);
    }

    @Test void longConversationCompactsAndNextRunReadsPersistedCheckpoint() {
        String session = store.createSession(agent.key());
        for (int i = 1; i <= 12; i++) {
            String run = store.beginRun(session, i == 1 ? "只统计已支付订单" : "q".repeat(360));
            store.finish(run, Message.assistant("a".repeat(360), List.of()));
        }
        ConversationContextService smallWindow = new ConversationContextService(json, tools, 7000, 512, 256);
        ModelClient model = (ModelClient) (request, delta) -> {
            if (request.tools().isEmpty()) {
                assertThat(request.maxOutputTokens()).isEqualTo(256);
                return new ModelReply("只统计已支付订单", List.of());
            }
            assertThat(request.maxOutputTokens()).isEqualTo(512);
            assertThat(request.messages()).extracting(Message::content)
                    .anyMatch(text -> text.contains("会话历史摘要") && text.contains("已支付"));
            return new ModelReply("按地区查询已支付订单", List.of());
        };
        AgentRunner runner = new AgentRunner(store, tools, toolExecution, agent, model, smallWindow, memories);
        ArrayList<RunEvent> events = new ArrayList<>();
        runner.execute(store.beginRun(session, "按地区拆分"), events::add);
        assertThat(events.getLast().type()).isEqualTo("run.completed");
        assertThat(store.snapshot(session).runs().getFirst().messages().getFirst().content()).isEqualTo("只统计已支付订单");
        String next = store.beginRun(session, "继续");
        String checkpointText = store.context(next).checkpoint().summary();
        assertThat(checkpointText).contains("已支付");
        assertThat(checkpointText).doesNotContain(LEGACY_ARCHIVE);
        runner.execute(next, events::add);
        assertThat(events.getLast().type()).isEqualTo("run.completed");
    }

    @Test void longToolChainCompactsInRunWithoutPersistingSyntheticMessages() {
        String session = store.createSession(agent.key());
        ConversationContextService smallWindow = new ConversationContextService(json, tools, 6200, 512, 256);
        // 用空白构造较大的真实协议参数，当前工具仍只执行一次同样的只读动作。
        AtomicInteger callNo = new AtomicInteger();
        AtomicInteger summaryNo = new AtomicInteger();
        String original = "把查询结果按地区展示";
        ModelClient model = (request, delta) -> {
            if (request.tools().isEmpty()) {
                // 记忆的固定提示词占用预算，可能需要折叠两批；仍逐批核对真实来源。
                int summary = summaryNo.incrementAndGet();
                request.usageListener().accept(30L, 2L);
                assertThat(request.messages().getFirst().content()).contains("inRun", "large-" + summary)
                        .doesNotContain("<memory>", "<available_skills>");
                assertThat(request.system()).doesNotContain("<memory>", "<available_skills>");
                return new ModelReply("已完成前面的时间查询，继续使用结果", List.of());
            }
            // 第一笔业务调用只上报输入，第二笔不上报，最后一笔实际输出为零。
            if (callNo.get() == 0) request.usageListener().accept(100L, null);
            if (callNo.get() == 2) request.usageListener().accept(80L, 0L);
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
        ArrayList<RunEvent> events = new ArrayList<>();
        new AgentRunner(store, tools, toolExecution, agent, model, smallWindow, memories)
                .execute(store.beginRun(session, original), events::add);
        assertThat(events.getLast().type()).isEqualTo("run.completed");
        assertThat(events.stream().filter(e -> "tool.started".equals(e.type()))).hasSize(2);
        assertThat(summaryNo.get()).isPositive();
        String runId = store.snapshot(session).runs().getLast().id();
        assertThat(jdbc.queryForMap(RUN_USAGE, runId)).containsEntry("business_calls", 3L)
                .containsEntry("summary_calls", (long) summaryNo.get())
                .containsEntry("input_tokens", 180L + 30L * summaryNo.get())
                .containsEntry("output_tokens", 2L * summaryNo.get())
                .containsEntry("input_reported_calls", 2L + summaryNo.get())
                .containsEntry("output_reported_calls", 1L + summaryNo.get());
        assertThat(store.snapshot(session).runs().getLast().messages()).extracting(Message::toolCallId)
                .contains("large-1", "large-2");
        assertThat(store.snapshot(session).runs().getLast().messages()).extracting(Message::content)
                .noneMatch(text -> text.contains("本轮已完成工作"));
    }

    @Test void recentQuestionUsesInlineHistoryWithoutOfferingCoordinateLookup() {
        String session = store.createSession(agent.key());
        String previous = store.beginRun(session, "现在几点了？");
        store.finish(previous, Message.assistant("上午九点", List.of()));
        ModelClient model = (request, delta) -> {
            assertThat(request.tools().toString()).doesNotContain(REMOVED_ARCHIVE_TOOL, "read_conversation_message");
            assertThat(request.messages()).extracting(Message::content)
                    .contains("[历史任务] 现在几点了？", "[本轮任务] 我刚才的问题是什么？");
            return new ModelReply("现在几点了？", List.of());
        };
        ArrayList<RunEvent> events = new ArrayList<>();
        new AgentRunner(store, tools, toolExecution, agent, model, contexts, memories)
                .execute(store.beginRun(session, "我刚才的问题是什么？"), events::add);
        assertThat(events.getLast().type()).isEqualTo(RunEvent.COMPLETED);
    }

    @Test void resumesLegacyCheckpointWithoutOfferingArchiveLookup() {
        String session = store.createSession(agent.key());
        String first = store.beginRun(session, ORDINARY);
        store.finish(first, Message.assistant(ORDINARY, List.of()));
        String folded = store.beginRun(session, ORDINARY);
        String summary = "旧摘要";
        store.finishWithContext(folded, Message.assistant(ORDINARY, List.of()),
                new SessionStore.Checkpoint(1, summary + "\n" + LEGACY_ARCHIVE + "a".repeat(64)));
        ModelClient model = (request, delta) -> {
            assertThat(request.tools().toString()).doesNotContain(REMOVED_ARCHIVE_TOOL);
            assertThat(request.messages()).extracting(Message::content)
                    .anyMatch(text -> text.contains(summary)).noneMatch(text -> text.contains(LEGACY_ARCHIVE));
            return new ModelReply(ORDINARY, List.of());
        };
        new AgentRunner(store, tools, toolExecution, agent, model, contexts, memories).execute(store.beginRun(session, ORDINARY), event -> {});
    }

    @Test void boundedToolPreviewPreservesOriginalHistory() {
        String session = store.createSession(agent.key());
        String historic = store.beginRun(session, ORDINARY);
        String prefix = "甲".repeat(999);
        String full = prefix + "😀" + "未截断的尾部".repeat(80);
        Message.ToolCall call = new Message.ToolCall("report", "report", "{}");
        store.append(historic, Message.assistant("", List.of(call)));
        store.append(historic, Message.result(call, full, true));
        store.finish(historic, Message.assistant(ORDINARY, List.of()));
        for (int i = 0; i < 4; i++) {
            store.finish(store.beginRun(session, ORDINARY), Message.assistant(ORDINARY, List.of()));
        }

        // 老结果的预览保留调用配对和 Unicode 边界，聊天记录仍可查看全文。
        ModelClient model = (request, delta) -> {
            assertThat(request.messages()).filteredOn(message -> call.id().equals(message.toolCallId()))
                    .singleElement().satisfies(message -> assertThat(message.content()).isEqualTo(prefix + "…（工具结果已截短）"));
            return new ModelReply(ORDINARY, List.of());
        };
        ArrayList<RunEvent> events = new ArrayList<>();
        new AgentRunner(store, tools, toolExecution, agent, model, contexts, memories).execute(store.beginRun(session, ORDINARY), events::add);
        assertThat(events.getLast().type()).isEqualTo(RunEvent.COMPLETED);
        assertThat(store.snapshot(session).runs().getFirst().messages()).extracting(Message::content).contains(full);
    }

    @Test void steersOnlyBetweenCompleteToolBatches() {
        String session = store.createSession(agent.key());
        String run = store.beginRun(session, "首问");
        AtomicInteger calls = new AtomicInteger();
        ArrayList<RunEvent> events = new ArrayList<>();
        Message.ToolCall first = new Message.ToolCall("a", "current_time", "{}");
        Message.ToolCall second = new Message.ToolCall("b", "current_time", "{}");
        ModelClient fake = (request, onText) -> {
            if (calls.incrementAndGet() == 1) return new ModelReply("", List.of(first, second));
            assertThat(request.messages()).extracting(Message::role).containsExactly("user", "assistant", "tool", "tool", "user", "user");
            assertThat(request.messages().subList(2, 4)).extracting(Message::toolCallId).containsExactly("a", "b");
            assertThat(request.messages()).extracting(Message::content).endsWith("[本轮补充] 补一", "[本轮补充] 补二");
            return new ModelReply("完成", List.of());
        };
        new AgentRunner(store, tools, toolExecution, agent, fake, contexts, memories).execute(run, event -> {
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
        SessionStore.RunView transcript = store.snapshot(session).runs().getFirst();
        assertThat(transcript.steers()).extracting(SessionStore.SteerReceipt::messageSeq).containsExactly(4, 5);
    }

    @Test void failsAfterCommittedReplyWithoutDuplicatingPartialOrCheckpoint() {
        String session = store.createSession(agent.key());
        String old = store.beginRun(session, "旧口径");
        store.finish(old, Message.assistant("旧回答", List.of()));
        String saved = store.beginRun(session, "已总结");
        SessionStore.Checkpoint checkpoint = new SessionStore.Checkpoint(1, "已确认旧口径");
        store.finishWithContext(saved, Message.assistant("已保存", List.of()), checkpoint);
        String run = store.beginRun(session, "首问");
        AtomicInteger calls = new AtomicInteger();
        ModelClient failure = (request, onText) -> {
            if (calls.incrementAndGet() == 1) {
                onText.accept("已显示回答");
                store.acceptSteer(session, run, UUID.randomUUID(), "补充原文");
                return new ModelReply("已显示回答", List.of());
            }
            throw new IllegalStateException("模型不可用");
        };
        ArrayList<RunEvent> events = new ArrayList<>();
        new AgentRunner(store, tools, toolExecution, agent, failure, contexts, memories).execute(run, events::add);
        assertThat(events.getLast().type()).isEqualTo(RunEvent.FAILED);
        SessionStore.RunView failed = store.snapshot(session).runs().getLast();
        assertThat(failed.messages()).extracting(Message::content).containsExactly("首问", "已显示回答", "补充原文");
        assertThat(failed.steers().getFirst().messageSeq()).isEqualTo(2);
        String next = store.beginRun(session, "下一轮");
        assertThat(store.context(next).checkpoint()).isEqualTo(checkpoint);
        assertThat(store.context(next).history()).extracting(SessionStore.RunView::id).doesNotContain(run);
        store.fail(next, "结束");
    }

    @Test void stopsOnThirtiethRequest() {
        String session = store.createSession(agent.key());
        AtomicInteger count = new AtomicInteger();
        AgentRunner runner = new AgentRunner(store, tools, toolExecution, agent, (request, delta) ->
                new ModelReply("", List.of(new Message.ToolCall("t" + count.incrementAndGet(), "current_time", "{}"))), contexts, memories);
        ArrayList<RunEvent> events = new ArrayList<>();
        runner.execute(store.beginRun(session, "loop"), events::add);
        assertThat(count.get()).isEqualTo(30);
        assertThat(events).filteredOn(e -> RunEvent.STEER_GATE.equals(e.type()))
                .singleElement().satisfies(e -> assertThat(e.steerCloseReason()).isEqualTo(SessionStore.LIMIT));
        assertThat(events.stream().filter(e -> "tool.started".equals(e.type())).count()).isEqualTo(29);
        assertThat(events.stream().filter(e -> "run.failed".equals(e.type())).count()).isEqualTo(1);
        assertThat(store.snapshot(session).activeRunId()).isNull();
    }

    @Test void interruptedStreamPreservesPartialTextAndReleasesSession() {
        String session = store.createSession(agent.key());
        AgentRunner runner = new AgentRunner(store, tools, toolExecution, agent, (request, delta) -> {
            delta.accept("未完成");
            throw new IllegalStateException("连接中断");
        }, contexts, memories);
        ArrayList<RunEvent> events = new ArrayList<>();
        runner.execute(store.beginRun(session, "hi"), events::add);
        assertThat(events.getLast().type()).isEqualTo("run.failed");
        assertThat(store.snapshot(session).runs().getLast().messages().getLast().content()).isEqualTo("未完成");
        String emptyRun = store.beginRun(session, "empty");
        new AgentRunner(store, tools, toolExecution, agent, (request, delta) -> new ModelReply(" ", List.of()), contexts, memories)
                .execute(emptyRun, events::add);
        SessionStore.RunView failed = store.snapshot(session).runs().getLast();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.messages()).extracting(Message::role).containsExactly(Message.USER);
        assertThat(jdbc.queryForMap(RUN_USAGE, emptyRun)).containsEntry("business_calls", 3L);
    }

    @Test void retriesOnlyTheInterruptedExchangeThenUsesTheSuccessfulToolSummary() throws Exception {
        // 用真实 SSE 流模拟 Ollama：工具已执行，续答断流，再次请求只返回推理。
        String tool = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":["
                + "{\"type\":\"function_call\",\"call_id\":\"a\",\"name\":\"current_time\",\"arguments\":\"{}\"}]}}\n\n";
        String thought = "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"private-trace\"}\n\n";
        String empty = thought + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":["
                + "{\"type\":\"reasoning\",\"encrypted_content\":\"private-trace\",\"summary\":[]}]}}\n\n";
        String answered = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":["
                + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"已回答新问题\"}]}]}}\n\n";
        List<String> responses = List.of(tool, thought, empty, empty, answered);
        List<String> requests = new CopyOnWriteArrayList<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(responses.get(requests.size() - 1).getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            ModelClient client = new ResponsesClient(json, URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "", Duration.ofSeconds(5));
            AgentRunner runner = new AgentRunner(store, tools, toolExecution, agent, client, contexts, memories);
            String session = store.createSession(agent.key());
            String run = store.beginRun(session, "现在几点？");
            List<RunEvent> events = new ArrayList<>();
            runner.execute(run, events::add);
            assertThat(requests).hasSize(3);
            var timeTool = json.readTree(requests.getFirst()).path("tools").get(0);
            assertThat(timeTool.path("type").asString()).isEqualTo("function");
            assertThat(timeTool.path("name").asString()).isEqualTo(CurrentTimeTool.NAME);
            assertThat(timeTool.path("parameters").isObject()).isTrue();
            assertThat(requests.get(2)).isEqualTo(requests.get(1));
            assertThat(events).filteredOn(e -> RunEvent.TOOL_STARTED.equals(e.type())).hasSize(1);
            assertThat(events.getLast().type()).isEqualTo(RunEvent.COMPLETED);
            var messages = store.snapshot(session).runs().getLast().messages();
            String timestamp = json.readTree(messages.get(2).content()).path("timestamp").asString();
            assertThat(events.getLast().text()).contains(timestamp).isEqualTo(messages.getLast().content());
            assertThat(jdbc.queryForMap(RUN_USAGE, run)).containsEntry("business_calls", 3L);

            // 新一轮只有推理时重试，交付本轮正文，不复用上轮工具摘要。
            runner.execute(store.beginRun(session, "另一个问题"), events::add);
            assertThat(requests).hasSize(5);
            assertThat(json.readTree(requests.get(4)).path("input")).isEqualTo(json.readTree(requests.get(3)).path("input"));
            assertThat(json.readTree(requests.get(4)).path("instructions").asString()).contains("上次响应没有可见回答");
            assertThat(events.getLast().type()).isEqualTo(RunEvent.COMPLETED);
            assertThat(events.getLast().text()).isEqualTo("已回答新问题");
            assertThat(json.writeValueAsString(store.snapshot(session))).doesNotContain("private-trace");
        } finally {
            server.stop(0);
        }
    }

    @Test void boundsRetriesAndPreservesAlreadyDeliveredText() {
        // 没有交付正文时最多尝试三次；已交付部分正文的请求只尝试一次。
        for (boolean emitted : List.of(false, true)) {
            String session = store.createSession(agent.key());
            String run = store.beginRun(session, ORDINARY);
            ModelClient interrupted = (request, delta) -> {
                if (emitted) delta.accept("已显示部分");
                throw new ModelClient.InterruptedResponse("模型连接中断", null);
            };
            List<RunEvent> events = new ArrayList<>();
            new AgentRunner(store, tools, toolExecution, agent, interrupted, contexts, memories).execute(run, events::add);
            assertThat(events.getLast().type()).isEqualTo(RunEvent.FAILED);
            assertThat(jdbc.queryForMap(RUN_USAGE, run)).containsEntry("business_calls", emitted ? 1L : 3L);
            if (emitted) {
                assertThat(store.snapshot(session).runs().getLast().messages().getLast().content()).isEqualTo("已显示部分");
            }
        }
    }

    @Test void newSteerCannotUsePreviousToolSummaryAsItsAnswer() {
        String session = store.createSession(agent.key());
        String run = store.beginRun(session, "现在几点？");
        AtomicInteger attempts = new AtomicInteger();
        ModelClient client = (request, delta) -> {
            if (attempts.incrementAndGet() == 1) {
                return new ModelReply("正在查询时间", List.of(new Message.ToolCall("a", CurrentTimeTool.NAME, "{}")));
            }
            throw new ModelClient.EmptyResponse("模型正常结束但仅返回推理");
        };
        List<RunEvent> events = new ArrayList<>();
        new AgentRunner(store, tools, toolExecution, agent, client, contexts, memories).execute(run, event -> {
            events.add(event);
            if (RunEvent.TOOL_FINISHED.equals(event.type())) store.acceptSteer(session, run, UUID.randomUUID(), ORDINARY);
        });
        assertThat(attempts.get()).isEqualTo(4);
        assertThat(events.getLast().type()).isEqualTo(RunEvent.FAILED);
        assertThat(events.getLast().text()).contains("模型未返回回答");
        SessionStore.RunView failed = store.snapshot(session).runs().getLast();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.messages().getLast().role()).isEqualTo(Message.USER);
    }

    @Test void failedToolCannotBePresentedAsACompletedOperation() {
        String session = store.createSession(agent.key());
        AtomicInteger attempts = new AtomicInteger();
        ModelClient client = (request, delta) -> {
            if (attempts.incrementAndGet() == 1) {
                return new ModelReply("已完成换算", List.of(new Message.ToolCall("a", CurrentTimeTool.NAME, "{}"),
                        new Message.ToolCall("b", ConvertTimezoneTool.NAME, "{}")));
            }
            throw new ModelClient.EmptyResponse("模型正常结束但仅返回推理");
        };
        List<RunEvent> events = new ArrayList<>();
        new AgentRunner(store, tools, toolExecution, agent, client, contexts, memories)
                .execute(store.beginRun(session, ORDINARY), events::add);
        assertThat(attempts.get()).isEqualTo(4);
        assertThat(events.getLast().type()).isEqualTo(RunEvent.FAILED);
        assertThat(events.getLast().text()).contains("模型未返回回答");
        assertThat(events).filteredOn(e -> RunEvent.TOOL_STARTED.equals(e.type())).hasSize(1);
        assertThat(events).filteredOn(e -> e.result() != null && Boolean.FALSE.equals(e.result().success())).hasSize(1);
    }
}
