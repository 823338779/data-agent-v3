package com.datagent.v3.tool;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.datagent.v3.agent.AgentDefinition;
import com.datagent.v3.model.Message.ToolCall;

class ToolRegistryTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T01:00:00Z"), ZoneOffset.UTC);
    private final AgentDefinition agent = AgentDefinition.general("test");
    private final List<AgentTool> tools = List.of(new CurrentTimeTool(clock), new ConvertTimezoneTool());
    private final ToolRegistry registry = new ToolRegistry(tools, agent, new JsonMapper());

    @Test void usesFixedClockAndConvertsTheSameInstant() {
        var starts = new AtomicInteger();
        var now = registry.invoke(agent, new ToolCall("a", "current_time", "{}"), starts::incrementAndGet);
        assertThat(now.content()).contains("2026-11-26T01:00:00Z");
        var tokyo = registry.invoke(agent, new ToolCall("b", "convert_timezone",
                "{\"timestamp\":\"2026-11-26T01:00:00Z\",\"timezone\":\"Asia/Tokyo\"}"), starts::incrementAndGet);
        assertThat(tokyo.content()).contains("2026-11-26T10:00:00+09:00");
        assertThat(starts.get()).isEqualTo(2);
    }

    @Test void invalidCallsNeverStart() {
        var restricted = new AgentDefinition("test", "prompt", "test", List.of("convert_timezone"));
        var invalid = List.of(new ToolCall("a", "current_time", "{}"),
                new ToolCall("b", "convert_timezone", "{\"timestamp\":\"2026-11-26T01:00:00\",\"timezone\":\"UTC\"}"),
                new ToolCall("c", "convert_timezone", "{\"timestamp\":\"2026-11-26T01:00:00Z\",\"timezone\":\"Mars\"}"),
                new ToolCall("d", "convert_timezone", "[]"));
        for (var call : invalid) {
            var result = registry.invoke(restricted, call, () -> fail("invalid call started"));
            assertThat(result.success()).isFalse();
            assertThat(result.toolCallId()).isEqualTo(call.id());
        }
        assertThat(registry.visibleTools(restricted)).hasSize(1);
    }

    @Test void rejectsDuplicateAndMissingToolsAtStartup() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(tools.getFirst(), tools.getFirst()), agent, new JsonMapper()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolRegistry(List.of(tools.getFirst()), agent, new JsonMapper()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
