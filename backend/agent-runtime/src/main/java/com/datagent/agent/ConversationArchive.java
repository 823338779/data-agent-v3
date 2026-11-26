package com.datagent.agent;

import com.datagent.model.Message;
import com.datagent.session.SessionStore;
import com.datagent.tool.AgentTool;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 绑定活动 Run 的归档读写；模型只能提供系统签发的数据库引用。 */
final class ConversationArchive implements AgentTool {
    static final String NAME = "read_archive", MARKER = "[DATUS_ARCHIVED] path=", PREVIEW = " preview=";
    private static final String PATH = "path", OFFSET = "offset", LIMIT = "limit";
    private static final String CONTENT = "content", NEXT = "nextOffset", NEWLINE = "\n";
    private static final String DESCRIPTION = "只读取上下文中标注的 PostgreSQL 归档路径；普通追问直接使用近期对话。";
    private static final Map<String, String> PARAMS = Map.of(PATH, "复制标记中的完整 pg: 路径",
            OFFSET, "可选：字符偏移，首页为 0", LIMIT, "可选：本页字符数，最多 1000");
    private static final Pattern VALID_PATH = Pattern.compile("pg:[0-9a-f]{64}");
    private static final Pattern REFERENCE = Pattern.compile(Pattern.quote(MARKER) + VALID_PATH.pattern());
    // pg: 前缀加 SHA-256 十六进制编码固定占 67 个字符。
    static final int MARKER_OVERHEAD = MARKER.length() + 67 + PREVIEW.length();
    private static final int PAGE = 1000;
    /** 读写均以当前活动 Run 限定会话。 */
    private final SessionStore sessions;
    private final String runId;
    /** 消息快照按行编码，正文中的换行由 JSON 转义。 */
    private final ObjectMapper json;
    /** 避免预算预检时反复写入同一份工具正文。 */
    private final Map<String, String> outputs = new HashMap<>();
    /** 当前业务请求实际收到的归档路径。 */
    private final Set<String> visiblePaths = new HashSet<>();

    ConversationArchive(SessionStore sessions, String runId, ObjectMapper json) {
        this.sessions = sessions;
        this.runId = runId;
        this.json = json;
    }

    String output(String body) {
        return outputs.computeIfAbsent(body, text -> sessions.saveArchive(runId, text));
    }

    String transcript(int throughTurn) {
        // 快照保留完整工具正文，沿用与模型历史相同的记忆隔离规则。
        var messages = ConversationContextService.withoutMemoryToolData(sessions.archiveMessages(runId, throughTurn));
        String body = messages.stream().map(json::writeValueAsString).collect(Collectors.joining(NEWLINE));
        return MARKER + sessions.saveArchive(runId, body);
    }

    /** 同一次模型预检即使分段摘要多次，也只导出一次完整的压缩前对话。 */
    Supplier<String> beforeCompaction(int turn) {
        return new Supplier<>() {
            /** 当前预检的不可变原文引用。 */
            private String pointer;
            public String get() {
                if (pointer == null) pointer = transcript(turn);
                return pointer;
            }
        };
    }

    static boolean referenced(List<Message> messages) {
        return messages.stream().anyMatch(m -> !Message.USER.equals(m.role()) && REFERENCE.matcher(m.content()).find());
    }

    boolean bind(List<Message> messages) {
        visiblePaths.clear();
        for (var message : messages) {
            if (Message.USER.equals(message.role())) continue;
            var matcher = REFERENCE.matcher(message.content());
            while (matcher.find()) visiblePaths.add(matcher.group().substring(MARKER.length()));
        }
        return !visiblePaths.isEmpty();
    }

    @Override public String name() { return NAME; }
    @Override public String description() { return DESCRIPTION; }
    @Override public Map<String, String> parameters() { return PARAMS; }
    @Override public String parameterType(String field) { return PATH.equals(field) ? STRING : INTEGER; }
    @Override public Set<String> requiredParameters() { return Set.of(PATH); }

    private static int pageNumber(JsonNode args, String name, int fallback) {
        if (!args.has(name)) return fallback;
        var value = args.get(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new InputError("分页参数必须为有效整数");
        return value.asInt();
    }

    @Override public Supplier<Map<String, String>> prepare(JsonNode args) {
        var path = args.get(PATH).asString();
        if (!visiblePaths.contains(path)) throw new InputError("请复制归档标记中的完整 pg: 路径");
        int offset = pageNumber(args, OFFSET, 0);
        int limit = pageNumber(args, LIMIT, PAGE);
        if (offset < 0 || offset == Integer.MAX_VALUE || limit < 1 || limit > PAGE) {
            throw new InputError("归档偏移或页大小无效");
        }
        return () -> {
            // 多取一个 Unicode 字符判断是否还有下一页，避免拆开代理对。
            var text = sessions.archivePage(runId, path, offset, limit + 1);
            if (text == null) throw new InputError("本会话中没有该归档或偏移越界，请检查归档路径和游标");
            boolean more = text.codePointCount(0, text.length()) > limit;
            return Map.of(CONTENT, more ? text.substring(0, text.offsetByCodePoints(0, limit)) : text,
                    NEXT, more ? Long.toString((long) offset + limit) : "");
        };
    }
}
