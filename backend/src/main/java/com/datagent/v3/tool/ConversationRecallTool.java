package com.datagent.v3.tool;

import com.datagent.v3.session.SessionStore;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 每轮只读工具：从当前 Run 推导会话，按消息位置读取原文的一页。 */
public final class ConversationRecallTool implements AgentTool {
    public static final String NAME = "read_conversation_message";
    private static final String DESCRIPTION_TEXT = "按轮次和消息序号回读本会话的原始消息；从序号 0 读取该轮用户原话。";
    private static final String TURN = "turn", SEQ = "seq", OFFSET = "offset";
    private static final String CONTENT = "content", NEXT_OFFSET = "nextOffset", ROLE = "role";
    private static final int PAGE_CHARS = 768;

    /** 存储和本次调用的服务端 Run 身份。 */
    private final SessionStore sessions;
    private final String runId;
    /** 把消息带角色和工具参数编码成可分页的完整记录。 */
    private final ObjectMapper json;

    public ConversationRecallTool(SessionStore sessions, String runId, ObjectMapper json) {
        this.sessions = sessions;
        this.runId = runId;
        this.json = json;
    }

    @Override public String name() { return NAME; }
    @Override public String description() { return DESCRIPTION_TEXT; }

    @Override public Map<String, String> parameters() {
        return Map.of(TURN, "轮次，从 1 开始", SEQ, "消息序号，从 0 开始", OFFSET, "字符偏移，首页为 0");
    }

    @Override public Supplier<Map<String, String>> prepare(JsonNode arguments) {
        // 参数解析和数据库读取分开，回查不执行原来的业务工具。
        int turn = Integer.parseInt(arguments.get(TURN).asString());
        int seq = Integer.parseInt(arguments.get(SEQ).asString());
        int offset = Integer.parseInt(arguments.get(OFFSET).asString());
        if (turn < 1 || seq < 0 || offset < 0) throw new IllegalArgumentException("无效的历史消息位置");
        return () -> {
            var message = sessions.recall(runId, turn, seq);
            if (message == null) throw new IllegalArgumentException("历史消息不存在");
            var raw = json.writeValueAsString(message);
            if (offset > raw.length() || (offset < raw.length() && Character.isLowSurrogate(raw.charAt(offset)))) {
                throw new IllegalArgumentException("无效的历史页位置");
            }
            int end = offset + Math.min(PAGE_CHARS, raw.length() - offset);
            if (end < raw.length() && Character.isLowSurrogate(raw.charAt(end))) end--;
            return Map.of(ROLE, message.role(), CONTENT, raw.substring(offset, end),
                    NEXT_OFFSET, end < raw.length() ? Integer.toString(end) : "");
        };
    }
}
