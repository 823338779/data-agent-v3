package com.datagent.tool;

import java.util.*;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/** 校验参数后返回待执行操作，使运行时能精确发布工具开始事件。 */
public interface AgentTool {
    String TYPE = "type";
    String OBJECT = "object";
    String STRING = "string";
    String INTEGER = "integer";
    String DESCRIPTION = "description";
    String PROPERTIES = "properties";
    String REQUIRED = "required";
    String ADDITIONAL = "additionalProperties";
    String name();
    String description();
    Map<String, String> parameters();
    Supplier<Map<String, String>> prepare(JsonNode arguments);

    /** 工具参数默认使用字符串，分页参数可声明为整数。 */
    default String parameterType(String field) { return STRING; }

    /** 默认要求全部参数，分页工具可为游标提供默认值。 */
    default Set<String> requiredParameters() { return parameters().keySet(); }

    /** 工具主动返回给调用方的可纠正错误。 */
    class InputError extends IllegalArgumentException {
        public InputError(String message) { super(message); }
    }

    default Map<String, Object> schema() {
        var fields = new LinkedHashMap<String, Object>();
        parameters().forEach((key, desc) -> fields.put(key, Map.of(TYPE, parameterType(key), DESCRIPTION, desc)));
        return Map.of(TYPE, OBJECT, PROPERTIES, fields, REQUIRED, List.copyOf(requiredParameters()), ADDITIONAL, false);
    }
}
