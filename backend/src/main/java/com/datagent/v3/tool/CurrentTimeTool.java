package com.datagent.v3.tool;

import java.time.Clock;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
public class CurrentTimeTool implements AgentTool {
    public static final String NAME = "current_time";
    private static final String HELP = "查询此刻的 UTC 时间，返回带 Z 的 ISO 8601 时间戳。";
    static final String TIMESTAMP = "timestamp";
    /** 时钟由运行时注入，测试可固定当前瞬间。 */
    private final Clock clock;

    public String name() { return NAME; }
    public String description() { return HELP; }
    public Map<String, String> parameters() { return Map.of(); }

    public Supplier<Map<String, String>> prepare(JsonNode arguments) {
        return () -> Map.of(TIMESTAMP, clock.instant().toString());
    }
}
