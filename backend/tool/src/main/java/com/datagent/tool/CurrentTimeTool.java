package com.datagent.tool;

import com.datagent.model.Message;
import java.time.Clock;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import tools.jackson.databind.JsonNode;

@Component
@Order(1)
@RequiredArgsConstructor
public class CurrentTimeTool implements AgentTool {
    public static final String NAME = "current_time";
    private static final String HELP = "查询当前 UTC 时间，参数为 {}。查询某地现在几点时先调用本工具，再将回执里的 timestamp 交给 convert_timezone。";
    static final String TIMESTAMP = "timestamp";
    /** 时钟由运行时注入，测试可固定当前瞬间。 */
    private final Clock clock;

    public String name() { return NAME; }
    public String description() { return HELP; }
    public Map<String, String> parameters() { return Map.of(); }

    public Supplier<Message> prepare(Call call, JsonNode arguments) {
        // 时间戳也可直接作为空正文时的工具摘要。
        return () -> {
            String timestamp = clock.instant().toString();
            return call.success(Map.of(TIMESTAMP, timestamp, SUMMARY, timestamp));
        };
    }
}
