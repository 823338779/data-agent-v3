package com.datagent.tool;

import com.datagent.model.Message;
import com.datagent.persistence.MemoryRepository;
import com.datagent.persistence.MemoryRepository.Snapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 长期记忆的读取、追加和精确修改。 */
@Component
public final class MemoryTool implements AgentTool {
    /** 统一工具名、操作选择器和模型可选的三个操作值。 */
    public static final String NAME = "memory", ACTION = "action", READ = "read", ADD = "add", EDIT = "edit";
    private static final String TOOL_DESCRIPTION = """
            管理长期偏好。调用示例：
            查看 {"action":"read"}
            新增 {"action":"add","content":"新偏好"}
            若原文为“周报：每周 2 次”：
            {"action":"edit","old_string":"周报：每周 2 次","new_string":"周报：每周 3 次"}
            千万注意"old_string"要按照原文，不能改编，不能修改标点的中英文、半角全角格式
            """;

    private static final String LF = "\n", DEFAULT = "默认";
    private static final String ADDED = "added", UPDATED = "updated", DELETED = "deleted", UNCHANGED = "unchanged";
    /** 可作为最终回答的操作回执，只描述已经完成的动作。 */
    private static final String READ_SUMMARY = "已读取长期记忆。";
    private static final Map<String, String> WRITE_SUMMARIES = Map.of(ADDED, "已新增记忆条目。",
            UPDATED, "已更新记忆条目。", DELETED, "已删除记忆条目。", UNCHANGED, "记忆条目未发生变化。");
    private static final String CODE = "code", MESSAGE = "message";
    private static final String OPERATION = "operation", ENTRY = "entry", REMAINING = "remainingBytes", NEEDED = "bytesNeeded";
    private static final String NO_MATCH = "NO_MATCH", MULTIPLE_MATCHES = "MULTIPLE_MATCHES";
    private static final String CONFLICT = "CONFLICT", FULL = "FULL", INVALID_ENTRY = "INVALID_ENTRY";
    private static final Pattern UNSAFE_ENTRY = Pattern.compile(
            "[\\p{Cntrl}\\p{Zl}\\p{Zp}]|(?:密码|令牌|私钥|凭据|api[ _-]?key|password|bearer\\s|"
            + "sk-[\\w-]{10,}|gh[pousr]_[\\w]+|AKIA[A-Z0-9]+|[a-z][a-z0-9+.-]*://[^\\s]+:[^\\s]+@)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SCOPE_TEXT = Pattern.compile("[\\p{L}\\p{N} _（）()·/-]+");

    /** 调用方绑定记忆归属，并在写操作期间持有事务。 */
    public interface Store extends Context {
        /** 产生这批工具调用的模型请求所看到的记忆版本。 */
        long requestRevision();
        Snapshot read();
        Snapshot readForUpdate();
        boolean update(long revision, String content);
    }

    /** 已应用到行列表的变更及其回执内容。 */
    private record Change(String operation, String entry) {}

    @Override public String name() { return NAME; }
    @Override public String description() { return TOOL_DESCRIPTION; }
    @Override public Map<String, String> parameters() { return PARAMETERS; }
    @Override public Set<String> requiredParameters() { return Set.of(ACTION); }

    /** 记忆工具的业务参数名。 */
    public static final String CONTENT = "content", OLD = "old_string", NEW = "new_string";

    /** 每种操作对应的参数和必填条件，展示给模型。 */
    private static final Map<String, String> PARAMETERS = Map.of(
            ACTION, "read、add 或 edit。",
            CONTENT, "add 必填，单行偏好。",
            OLD, "edit 必填。从最新正文逐字复制同一行内唯一的非空片段，保留空格数量、标点及全角半角。",
            NEW, "edit 必填，替换文本。空字符串删除匹配片段，匹配整行则删除条目。");
    /** 每个 action 接收的精确字段集合。 */
    private static final Map<String, Set<String>> ACTION_FIELDS = Map.of(
            READ, Set.of(ACTION), ADD, Set.of(ACTION, CONTENT), EDIT, Set.of(ACTION, OLD, NEW));

    @Override public Supplier<Message> prepare(Call call, JsonNode arguments) {
        if (!(call.context() instanceof Store store)) {
            throw new SecurityException("记忆操作缺少记忆读写入口");
        }
        // 参数有误时在发出工具开始事件前拒绝调用。
        String action = arguments.get(ACTION).asString();
        Set<String> fields = ACTION_FIELDS.get(action);
        if (fields == null) throw new InputError("未知记忆操作");
        AgentTool.requireParameters(arguments.propertyNames(), fields);
        if (!fields.equals(arguments.propertyNames())) throw new InputError("记忆操作参数与定义不符");

        // Supplier 根据 action 分派读写，记忆归属由调用方绑定。
        return () -> switch (action) {
            case READ -> read(call, store);
            case ADD -> write(call, store, ADD, lines -> add(lines, arguments.get(CONTENT).asString()));
            case EDIT -> write(call, store, EDIT, lines -> edit(lines,
                    arguments.get(OLD).asString(), arguments.get(NEW).asString()));
            default -> throw new InputError("未知记忆操作");
        };
    }

    private Message read(Call call, Store store) {
        Snapshot snapshot = store.read();
        return call.success(Map.of(CONTENT, snapshot.content(),
                REMAINING, Integer.toString(snapshot.remaining()), SUMMARY, READ_SUMMARY));
    }

    private Message write(Call call, Store store, String action, Function<List<String>, Change> apply) {
        // 读取当前版本后计算变更，使用该版本执行有条件更新。
        Snapshot snapshot = store.readForUpdate();
        // 返回最新快照，让模型按最新原文重新选取片段。
        if (EDIT.equals(action) && store.requestRevision() != snapshot.revision()) {
            return call.failed(Map.of(CODE, CONFLICT, CONTENT, snapshot.content(),
                    MESSAGE, "记忆已变化，请从当前 content 逐字复制要替换的 old_string。"));
        }
        List<String> lines = new ArrayList<>(snapshot.content().isEmpty()
                ? List.of() : List.of(snapshot.content().split(LF, -1)));
        Change change;
        try {
            change = apply.apply(lines);
        } catch (InputError invalid) {
            // 将原文交回模型，以便复制准确片段或扩大匹配范围。
            if (NO_MATCH.equals(invalid.getMessage()) || MULTIPLE_MATCHES.equals(invalid.getMessage())) {
                String hint = NO_MATCH.equals(invalid.getMessage())
                        ? "未找到非空的 old_string。请从 content 的同一行逐字复制，保留标点、空格及全角半角。"
                        : "old_string 匹配多处。请从 content 复制含相邻文字的唯一片段，并在 new_string 中保留仍需保留的文字。";
                return call.failed(Map.of(CODE, invalid.getMessage(), CONTENT, snapshot.content(), MESSAGE, hint));
            }
            return call.failed(Map.of(CODE, invalid.getMessage(), MESSAGE, "无法完成该条目变更。"));
        }
        String body = String.join(LF, lines);
        int remaining = MemoryRepository.LIMIT - MemoryRepository.bytes(body);
        if (remaining < 0) {
            return call.failed(Map.of(CODE, FULL, MESSAGE, "长期记忆的容量不足。", NEEDED, Integer.toString(-remaining)));
        }
        if (!body.equals(snapshot.content()) && !store.update(snapshot.revision(), body)) {
            return call.failed(Map.of(CODE, CONFLICT, MESSAGE, "记忆已并发更新，请重新读取。"));
        }
        return call.success(Map.of(OPERATION, change.operation(), ENTRY, change.entry(),
                REMAINING, Integer.toString(remaining), SUMMARY, WRITE_SUMMARIES.get(change.operation())));
    }

    private static Change add(List<String> lines, String entry) {
        if (lines.contains(entry)) return new Change(UNCHANGED, entry);
        validateEntry(entry);
        if (conflicts(lines, entry)) throw new InputError(CONFLICT);
        lines.add(entry);
        return new Change(ADDED, entry);
    }

    private static Change edit(List<String> lines, String oldText, String replacement) {
        // 逐行按原文查找；同一行内重叠出现或跨条目重复时均要求扩大匹配片段。
        if (oldText.isEmpty()) throw new InputError(NO_MATCH);
        int target = -1;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int first = line.indexOf(oldText);
            if (first < 0) continue;
            if (target >= 0 || first != line.lastIndexOf(oldText)) throw new InputError(MULTIPLE_MATCHES);
            target = index;
        }
        if (target < 0) throw new InputError(NO_MATCH);
        return replaceLine(lines, target, lines.get(target).replace(oldText, replacement));
    }

    private static Change replaceLine(List<String> lines, int index, String next) {
        // 对修改后的整条记忆沿用条目及冲突校验。
        String old = lines.get(index);
        if (old.equals(next)) return new Change(UNCHANGED, old);
        lines.remove(index);
        if (next.isEmpty()) return new Change(DELETED, old);
        validateEntry(next);
        if (lines.contains(next) || conflicts(lines, next)) throw new InputError(CONFLICT);
        lines.add(index, next);
        return new Change(UPDATED, next);
    }

    private static void validateEntry(String entry) {
        if (entry.isBlank() || UNSAFE_ENTRY.matcher(entry).find()) throw new InputError(INVALID_ENTRY);
    }

    private static String scope(String text) {
        int at = text.indexOf(DEFAULT);
        if (at <= 0 || at != text.lastIndexOf(DEFAULT) || defaultValue(text).isEmpty()) return null;
        String key = text.substring(0, at).strip();
        return SCOPE_TEXT.matcher(key).matches() ? key : null;
    }

    private static String defaultValue(String text) {
        return text.substring(text.indexOf(DEFAULT) + DEFAULT.length()).strip();
    }

    private static boolean conflicts(List<String> lines, String entry) {
        String key = scope(entry);
        return key != null && lines.stream().anyMatch(line -> key.equals(scope(line)));
    }

}
