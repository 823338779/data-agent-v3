package com.datagent.memory;

import java.util.regex.Pattern;

/** 授权只解析当前已纳入的真实用户原话，不解析模型可见的历史或提示词。 */
record MemoryIntent(Kind kind, String oldText, String newText) {
    enum Kind { NORMAL, VIEW, EXTRACT, ORGANIZE, IGNORE, CANCEL, CLARIFY, SAVE, EDIT, FORGET }
    private static final String DEFAULT = "默认";
    private static final Pattern REPLACE = Pattern.compile("把记忆「([^「」]+)」改为「([^「」]+)」");
    private static final Pattern FORGET = Pattern.compile("忘记「([^「」]+)」");
    private static final Pattern PREFERENCE = Pattern.compile("我(?:偏好|习惯).+");
    private static final Pattern SAVE_PREFIX = Pattern.compile("^(?:以后|今后|请记住|记住)\\s*[:：]?\\s*");
    private static final Pattern UNSAFE = Pattern.compile(
            "[\\p{Cntrl}\\p{Zl}\\p{Zp}。；;！？?!「」“”‘’`《》]|(?:密码|令牌|私钥|凭据|api[ _-]?key|password|bearer\\s|"
            + "sk-[\\w-]{10,}|gh[pousr]_[\\w]+|AKIA[A-Z0-9]+|[a-z][a-z0-9+.-]*://[^\\s]+:[^\\s]+@)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPOUND = Pattern.compile("(?:，|,|并且|同时|然后|另外|以及).*(记住|忘记|默认|改为|删除|清空|保存)");
    private static final Pattern SCOPE_TEXT = Pattern.compile("[\\p{L}\\p{N} _（）()·/-]+");
    private static final Pattern CANCEL = Pattern.compile("^(?:请)?(?:不用|别|不要|不必)(?:再)?(?:记住|保存|记录|存储)(?:。|$|[\\s，,：:]|这|刚|前|上|它).*");
    private static final Pattern OUTSIDE_MEMORY = Pattern.compile(
            "(?:指标口径|统计口径|参考\\s*SQL|SQL语句|团队知识|查询结果)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IGNORE = Pattern.compile("^这次忽略记忆(?:[，,：:].*)?");
    private static final Pattern READ = Pattern.compile("(?:你)?(?:现在)?(?:记住了什么|记住了哪些偏好|有什么长期记忆)|(?:查看|列出|显示)(?:已保存的)?(?:长期)?记忆");
    private static final Pattern EXTRACT = Pattern.compile("(?:总结|提炼)这次会话中?值得记住的偏好");
    private static final Pattern ORGANIZE = Pattern.compile("整理(?:一下)?(?:已保存的)?(?:长期)?记忆");

    static MemoryIntent parse(String raw) {
        var text = raw.strip();
        if (text.startsWith("这次忽略记忆") && IGNORE.matcher(endMark(text)).matches()) return readOnly(Kind.IGNORE);
        if (CANCEL.matcher(text).matches()) return readOnly(Kind.CANCEL);
        var readText = endMark(text);
        if (READ.matcher(readText).matches()) return readOnly(Kind.VIEW);
        if (EXTRACT.matcher(readText).matches()) return readOnly(Kind.EXTRACT);
        if (ORGANIZE.matcher(readText).matches()) return readOnly(Kind.ORGANIZE);

        // 未锚定的引述、疑问、多行输入不获得任何写权限。
        if (text.lines().count() != 1) return readOnly(Kind.NORMAL);
        text = terminalStop(text);
        if ((text.startsWith("忘记『") || text.startsWith("把记忆『")) && !text.contains("「") && !text.contains("」")) {
            text = text.replace('『', '「').replace('』', '」');
        }
        var edit = REPLACE.matcher(text);
        if (edit.matches()) {
            var old = edit.group(1);
            var next = edit.group(2);
            return safe(old) && valid(next) ? new MemoryIntent(Kind.EDIT, old, next) : readOnly(Kind.CLARIFY);
        }
        var forget = FORGET.matcher(text);
        if (forget.matches()) {
            return safe(forget.group(1)) ? new MemoryIntent(Kind.FORGET, forget.group(1), "") : readOnly(Kind.CLARIFY);
        }
        var prefix = SAVE_PREFIX.matcher(text);
        if (prefix.find()) {
            var body = text.substring(prefix.end()).strip();
            return valid(body) ? new MemoryIntent(Kind.SAVE, null, body) : readOnly(Kind.CLARIFY);
        }
        if (text.startsWith("忘记") || text.startsWith("把记忆")) return readOnly(Kind.CLARIFY);
        return readOnly(Kind.NORMAL);
    }

    static String scope(String text) {
        int at = text.indexOf(DEFAULT);
        if (at <= 0 || at != text.lastIndexOf(DEFAULT) || text.substring(at + DEFAULT.length()).isBlank()) return null;
        String scope = text.substring(0, at).strip();
        if (!SCOPE_TEXT.matcher(scope).matches() || scope.contains("引用") || scope.contains("假如")
                || scope.contains("如果") || scope.contains("假设") || scope.contains("请记住")) return null;
        return scope;
    }

    static String defaultValue(String entry) {
        return scope(entry) == null ? null : entry.substring(entry.indexOf(DEFAULT) + DEFAULT.length()).strip();
    }

    private static boolean safe(String text) { return !text.isBlank() && !UNSAFE.matcher(text).find(); }
    private static boolean valid(String text) {
        return safe(text) && !COMPOUND.matcher(text).find() && !OUTSIDE_MEMORY.matcher(text).find()
                && (scope(text) != null || PREFERENCE.matcher(text).matches());
    }
    private static String terminalStop(String text) {
        return text.endsWith("。") ? text.substring(0, text.length() - 1).stripTrailing() : text;
    }
    private static String endMark(String text) {
        return text.endsWith("？") || text.endsWith("?") ? text.substring(0, text.length() - 1).stripTrailing() : terminalStop(text);
    }
    private static MemoryIntent readOnly(Kind kind) { return new MemoryIntent(kind, null, null); }
}
