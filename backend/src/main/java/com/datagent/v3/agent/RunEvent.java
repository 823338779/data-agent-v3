package com.datagent.v3.agent;

import com.datagent.v3.model.Message;

/** seq 为运行内事件序号，messageSeq 为对应的助手消息位置，工具用调用 ID 配对。 */
public record RunEvent(String type, String runId, long seq, int messageSeq, String text,
                       Message.ToolCall call, Message result) {
    public static final String STARTED = "run.started";
    public static final String DELTA = "text.delta";
    public static final String TOOL_STARTED = "tool.started";
    public static final String TOOL_FINISHED = "tool.finished";
    public static final String COMPLETED = "run.completed";
    public static final String FAILED = "run.failed";

    public boolean terminal() {
        return COMPLETED.equals(type) || FAILED.equals(type);
    }
}
