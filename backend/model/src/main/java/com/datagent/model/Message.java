package com.datagent.model;

import java.util.List;

/** 一条完整模型消息。调用参数保留原文，结果通过 toolCallId 关联。 */
public record Message(String role, String content, List<ToolCall> calls, String toolCallId, Boolean success) {
    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";
    public static final String TOOL = "tool";
    public static final String SYSTEM = "system";

    /** id 为提供方调用标识，name 为注册工具名，arguments 为完整 JSON 参数。 */
    public record ToolCall(String id, String name, String arguments) {}

    public static Message user(String text) {
        return new Message(USER, text, List.of(), null, null);
    }

    public static Message assistant(String text, List<ToolCall> calls) {
        return new Message(ASSISTANT, text, List.copyOf(calls), null, null);
    }

    public static Message result(ToolCall call, String json, boolean success) {
        return new Message(TOOL, json, List.of(), call.id(), success);
    }
}
