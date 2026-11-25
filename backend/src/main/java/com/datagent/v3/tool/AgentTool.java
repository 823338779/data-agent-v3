package com.datagent.v3.tool;

import java.util.*;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/** 校验参数后返回待执行操作，使运行时能精确发布工具开始事件。 */
public interface AgentTool {
    String TYPE = "type";
    String OBJECT = "object";
    String STRING = "string";
    String DESCRIPTION = "description";
    String PROPERTIES = "properties";
    String REQUIRED = "required";
    String ADDITIONAL = "additionalProperties";
    String name();
    String description();
    Map<String, String> parameters();
    Supplier<Map<String, String>> prepare(JsonNode arguments);

    default Map<String, Object> schema() {
        var fields = new LinkedHashMap<String, Object>();
        parameters().forEach((key, desc) -> fields.put(key, Map.of(TYPE, STRING, DESCRIPTION, desc)));
        return Map.of(TYPE, OBJECT, PROPERTIES, fields, REQUIRED, List.copyOf(fields.keySet()), ADDITIONAL, false);
    }
}
