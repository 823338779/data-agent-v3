package com.datagent.v3.tool;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import static com.datagent.v3.tool.CurrentTimeTool.TIMESTAMP;

@Component
public class ConvertTimezoneTool implements AgentTool {
    public static final String NAME = "convert_timezone";
    private static final String HELP = "将指定瞬间换算到目标时区。";
    private static final String ZONE = "timezone";
    private static final Map<String, String> FIELDS = Map.of(TIMESTAMP, "含 UTC 偏移的 ISO 8601 时间", ZONE, "目标 IANA 时区名");

    public String name() { return NAME; }
    public String description() { return HELP; }
    public Map<String, String> parameters() { return FIELDS; }

    public Supplier<Map<String, String>> prepare(JsonNode arguments) {
        // 在开始事件前校验偏移与时区，换算保持同一瞬间。
        var instant = OffsetDateTime.parse(arguments.get(TIMESTAMP).asString());
        var zone = ZoneId.of(arguments.get(ZONE).asString());
        return () -> Map.of(TIMESTAMP, instant.atZoneSameInstant(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                ZONE, zone.getId());
    }
}
