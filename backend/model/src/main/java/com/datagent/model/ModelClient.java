package com.datagent.model;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 单次模型请求边界。上下文和工具清单由调用方提供。 */
public interface ModelClient {
    /** maxOutputTokens 限制输出；usageListener 接收端点实际报告的输入和输出 token，缺失维度为 null。 */
    record ModelRequest(String model, String system, List<Message> messages, List<Map<String, Object>> tools,
                        int maxOutputTokens, BiConsumer<Long, Long> usageListener) {
        public ModelRequest(String model, String system, List<Message> messages, List<Map<String, Object>> tools,
                            int maxOutputTokens) {
            this(model, system, messages, tools, maxOutputTokens, null);
        }

        /** 只绑定本次模型交换的统计接收方，不改变协议投影。 */
        public ModelRequest withUsageListener(BiConsumer<Long, Long> listener) {
            return new ModelRequest(model, system, messages, tools, maxOutputTokens, listener);
        }

        public ModelRequest(String model, String system, List<Message> messages, List<Map<String, Object>> tools) {
            this(model, system, messages, tools, 0);
        }
    }
    record ModelReply(String text, List<Message.ToolCall> calls) {}
    ModelReply complete(ModelRequest request, Consumer<String> onText);
}
