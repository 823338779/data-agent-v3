package com.datagent.v3.memory;

import com.datagent.v3.tool.AgentTool;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/** 仅定义模型可见协议，执行由绑定本次请求的记忆服务负责。 */
record MemoryTool(String name, String description, Map<String, String> parameters) implements AgentTool {
    @Override public Supplier<Map<String, String>> prepare(JsonNode arguments) {
        throw new IllegalStateException("记忆工具必须通过运行绑定执行");
    }
}
