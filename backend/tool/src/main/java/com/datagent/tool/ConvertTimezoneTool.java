package com.datagent.tool;

import com.datagent.model.Message;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import tools.jackson.databind.JsonNode;
import static com.datagent.tool.CurrentTimeTool.TIMESTAMP;

@Component
@Order(2)
public class ConvertTimezoneTool implements AgentTool {
    public static final String NAME = "convert_timezone";
    private static final String HELP = "将已知时间戳换算到目标时区，timestamp 和 timezone 均必填。用户已给出带偏移的时间时直接换算。";
    private static final String ZONE = "timezone";
    private static final Map<String, String> FIELDS = Map.of(TIMESTAMP, "用户指定或工具返回的 ISO 8601 时间戳，保留 Z 或 UTC 偏移。",
            ZONE, "目标地点的 IANA 时区名，例如东京用 Asia/Tokyo。");

    public String name() { return NAME; }
    public String description() { return HELP; }
    public Map<String, String> parameters() { return FIELDS; }

    public Supplier<Message> prepare(Call call, JsonNode arguments) {
        // 在开始事件前校验偏移与时区，换算保持同一瞬间。
        OffsetDateTime instant = OffsetDateTime.parse(arguments.get(TIMESTAMP).asString());
        ZoneId zone = ZoneId.of(arguments.get(ZONE).asString());
        // 摘要和工具字段使用同一换算结果。
        return () -> {
            String timestamp = instant.atZoneSameInstant(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return call.success(Map.of(TIMESTAMP, timestamp, ZONE, zone.getId(), SUMMARY, timestamp));
        };
    }
}
