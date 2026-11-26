package com.datagent.v3.model;

import java.util.*;
import java.util.function.Consumer;

/** 单次模型请求边界。上下文和工具清单由调用方提供。 */
public interface ModelClient {
    /** maxOutputTokens 限制端点生成的最大 token；零表示未指定。 */
    record ModelRequest(String model, String system, List<Message> messages, List<Map<String, Object>> tools,
                        int maxOutputTokens) {
        public ModelRequest(String model, String system, List<Message> messages, List<Map<String, Object>> tools) {
            this(model, system, messages, tools, 0);
        }
    }
    record ModelReply(String text, List<Message.ToolCall> calls) {}
    ModelReply complete(ModelRequest request, Consumer<String> onText);
}
