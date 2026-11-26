package com.datagent.v3.agent;

import com.datagent.v3.model.Message;
import java.util.UUID;

/** seq 为运行内事件序号，messageSeq 为对应的助手消息位置，工具用调用 ID 配对。 */
public record RunEvent(String type, String runId, long seq, int messageSeq, String text,
                       Message.ToolCall call, Message result, UUID clientMessageId, Boolean canSteer,
                       String steerCloseReason) {
    public RunEvent(String type, String runId, long seq, int messageSeq, String text, Message.ToolCall call, Message result) {
        this(type, runId, seq, messageSeq, text, call, result, null, null, null);
    }
    public static final String STARTED = "run.started";
    public static final String DELTA = "text.delta";
    public static final String TOOL_STARTED = "tool.started";
    public static final String TOOL_FINISHED = "tool.finished";
    public static final String COMPLETED = "run.completed";
    public static final String STEER_GATE = "run.steer_gate";
    public static final String STEER_INCORPORATED = "run.steer_incorporated";
    public static final String REPLY_COMMITTED = "run.reply_committed";
    public static final String FAILED = "run.failed";

    public boolean terminal() {
        return COMPLETED.equals(type) || FAILED.equals(type);
    }
}
