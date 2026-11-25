package com.datagent.v3.model;

import java.util.*;
import java.util.function.Consumer;

/** 单次模型请求边界。上下文和工具清单由调用方提供。 */
public interface ModelClient {
    record ModelRequest(String model, String system, List<Message> messages, List<Map<String, Object>> tools) {}
    record ModelReply(String text, List<Message.ToolCall> calls) {}
    ModelReply complete(ModelRequest request, Consumer<String> onText);
}
