package com.datagent.tool;

import com.datagent.model.Message;
import java.util.*;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
    String ERROR = "error";
    /** 成功回执中可直接展示给用户的简短结果。 */
    String SUMMARY = "summary";
    String name();
    String description();
    Map<String, String> parameters();

    /** 是否在后续轮次和摘要中保留调用及回执；本轮续答仍可使用回执。 */
    default boolean includeResultInHistory() { return true; }

    /** 参数结构已校验；具体操作未获授权时抛出 SecurityException，获准的操作在开始事件后执行。 */
    Supplier<Message> prepare(Call call, JsonNode arguments);

    /** 调用方提供本次执行所需的能力，各工具声明自己的上下文契约。 */
    interface Context {
        Context EMPTY = new Context() {};
    }

    /** request 保留原调用 ID，context 和 messages 来自当前请求，json 用于编码结果。 */
    record Call(Message.ToolCall request, Context context, List<Message> messages, ObjectMapper json) {
        public Message success(Map<String, String> payload) {
            return Message.result(request, json.writeValueAsString(payload), true);
        }

        /** 将可纠正的错误说明交还给发起调用的模型。 */
        public Message failed(String reason) {
            return failed(Map.of(ERROR, reason));
        }

        /** 保留错误码和业务详情，回执关联原工具调用 ID。 */
        public Message failed(Map<String, String> payload) {
            return Message.result(request, json.writeValueAsString(payload), false);
        }
    }

    /** 工具参数默认使用字符串，分页参数可声明为整数。 */
    default String parameterType(String field) { return STRING; }

    /** 默认要求全部参数，分页工具可为游标提供默认值。 */
    default Set<String> requiredParameters() { return parameters().keySet(); }

    /** 将缺失的必填字段写入错误回执，供模型在下一次请求中修正。 */
    static void requireParameters(Collection<String> supplied, Set<String> required) {
        if (supplied.containsAll(required)) return;
        Set<String> missing = new TreeSet<>(required);
        missing.removeAll(supplied);
        throw new InputError("缺少必填参数：" + missing);
    }

    /** 工具主动返回给调用方的可纠正错误。 */
    class InputError extends IllegalArgumentException {
        public InputError(String message) { super(message); }
    }

    default Map<String, Object> schema() {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        parameters().forEach((key, desc) -> fields.put(key, Map.of(TYPE, parameterType(key), DESCRIPTION, desc)));
        return Map.of(TYPE, OBJECT, PROPERTIES, fields, REQUIRED, List.copyOf(requiredParameters()), ADDITIONAL, false);
    }
}
