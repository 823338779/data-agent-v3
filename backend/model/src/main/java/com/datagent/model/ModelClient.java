package com.datagent.model;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/** 单次模型请求边界。上下文和工具清单由调用方提供。 */
public interface ModelClient {
    /** 传输失败或未收到流尾，可在没有交付正文时重试业务请求。 */
    class InterruptedResponse extends IllegalStateException {
        public InterruptedResponse(String message, Throwable cause) { super(message, cause); }
    }

    /** 已收到正常终止和流尾，但模型没有提供正文或工具调用。 */
    class EmptyResponse extends IllegalStateException {
        public EmptyResponse(String message) { super(message); }
    }

    /** maxOutputTokens 限制输出；usageListener 接收端点报告的用量；toolReasoning 按 messages 下标携带本次续答的推理。 */
    record ModelRequest(String model, String system, List<Message> messages, List<Map<String, Object>> tools,
                        int maxOutputTokens, BiConsumer<Long, Long> usageListener, Map<Integer, List<JsonNode>> toolReasoning) {
        public ModelRequest(String model, String system, List<Message> messages, List<Map<String, Object>> tools,
                            int maxOutputTokens, BiConsumer<Long, Long> usageListener) {
            this(model, system, messages, tools, maxOutputTokens, usageListener, Map.of());
        }

        public ModelRequest(String model, String system, List<Message> messages, List<Map<String, Object>> tools,
                            int maxOutputTokens) {
            this(model, system, messages, tools, maxOutputTokens, null);
        }

        /** 只绑定本次模型交换的统计接收方，不改变协议投影。 */
        public ModelRequest withUsageListener(BiConsumer<Long, Long> listener) {
            return new ModelRequest(model, system, messages, tools, maxOutputTokens, listener, toolReasoning);
        }

        public ModelRequest(String model, String system, List<Message> messages, List<Map<String, Object>> tools) {
            this(model, system, messages, tools, 0);
        }
    }
    /** toolReasoning 仅供下一次业务模型请求重放，不属于会话消息。 */
    record ModelReply(String text, List<Message.ToolCall> calls, List<JsonNode> toolReasoning) {
        public ModelReply(String text, List<Message.ToolCall> calls) {
            this(text, calls, List.of());
        }
    }
    ModelReply complete(ModelRequest request, Consumer<String> onText);
}
