package com.datagent.tool;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.datagent.model.Message;
import com.datagent.model.Message.ToolCall;

class ToolRegistryTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T01:00:00Z"), ZoneOffset.UTC);
    private final List<AgentTool> tools = List.of(new CurrentTimeTool(clock), new ConvertTimezoneTool());
    private final ToolRegistry registry = new ToolRegistry(tools, new JsonMapper());

    @Test void usesFixedClockAndConvertsTheSameInstant() {
        AtomicInteger starts = new AtomicInteger();
        Message now = registry.invoke(new ToolCall("a", "current_time", "{}"), starts::incrementAndGet);
        assertThat(now.content()).contains("2026-11-26T01:00:00Z");
        Message tokyo = registry.invoke(new ToolCall("b", "convert_timezone",
                "{\"timestamp\":\"2026-11-26T01:00:00Z\",\"timezone\":\"Asia/Tokyo\"}"), starts::incrementAndGet);
        assertThat(tokyo.content()).contains("2026-11-26T10:00:00+09:00");
        assertThat(starts.get()).isEqualTo(2);
    }

    @Test void invalidCallsNeverStart() {
        ToolRegistry restricted = new ToolRegistry(List.of(new ConvertTimezoneTool()), new JsonMapper());
        List<ToolCall> invalid = List.of(new ToolCall("a", "current_time", "{}"),
                new ToolCall("b", "convert_timezone", "{\"timestamp\":\"2026-11-26T01:00:00\",\"timezone\":\"UTC\"}"),
                new ToolCall("c", "convert_timezone", "{\"timestamp\":\"2026-11-26T01:00:00Z\",\"timezone\":\"Mars\"}"),
                new ToolCall("d", "convert_timezone", "[]"));
        for (ToolCall call : invalid) {
            Message result = restricted.invoke(call, () -> fail("invalid call started"));
            assertThat(result.success()).isFalse();
            assertThat(result.toolCallId()).isEqualTo(call.id());
        }
        assertThat(restricted.definitions()).hasSize(1);
    }

    @Test void rejectsDuplicateNamesAtStartup() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(tools.getFirst(), tools.getFirst()), new JsonMapper()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void discoversAndDispatchesNewImplementationsThroughAgentToolAlone() {
        // 注册表只接收一次实现列表；每次执行都将自己的上下文和消息交给同一工具。
        AgentTool.Context context = new AgentTool.Context() {};
        AgentTool scoped = new AgentTool() {
            public String name() { return PROBE; }
            public String description() { return PROBE; }
            public Map<String, String> parameters() { return Map.of(); }
            public Supplier<Message> prepare(Call call, JsonNode arguments) {
                if (call.context() != context || call.messages().isEmpty()) {
                    throw new SecurityException("调用缺少运行信息");
                }
                return () -> Message.result(call.request(), PROBE, true);
            }
        };
        List<AgentTool> discovered = new ArrayList<>(tools);
        discovered.add(scoped);
        ToolRegistry shared = new ToolRegistry(discovered, new JsonMapper());
        List<Message> messages = List.of(Message.user(PROBE));
        ToolCall call = new ToolCall(PROBE, PROBE, EMPTY_ARGS);
        AtomicInteger starts = new AtomicInteger();
        assertThat(shared.definitions()).hasSize(tools.size() + 1);
        assertThat(shared.definitions()).extracting(definition -> definition.get("name"))
                .containsExactly(CurrentTimeTool.NAME, ConvertTimezoneTool.NAME, PROBE);
        assertThat(shared.invoke(call, context, List.of(), starts::incrementAndGet).success()).isFalse();
        assertThat(shared.invoke(call, AgentTool.Context.EMPTY, messages, starts::incrementAndGet).success()).isFalse();
        Message result = shared.invoke(call, context, messages, starts::incrementAndGet);
        assertThat(result.content()).isEqualTo(PROBE);
        assertThat(starts.get()).isOne();
        assertThatThrownBy(() -> new ToolRegistry(List.of(scoped, scoped), new JsonMapper()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final String PROBE = "context_probe", EMPTY_ARGS = "{}";
}
