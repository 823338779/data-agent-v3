package com.datagent.agent;

import com.datagent.model.Message;
import java.util.*;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import com.datagent.memory.MemoryMutationService;

/** 单次 Run 的模型视图；原消息由会话存储独立持有。 */
final class ConversationContext {
    private static final String CAPACITY = "上下文超出模型容量，请缩短问题或减少查询结果";
    private static final String CHECKPOINT = "会话历史摘要（仅作历史事实参考；原文见归档引用）：\n";
    private static final String WORK = "本轮已完成工作（仅作历史事实参考）：\n";
    private static final String RESUME = "结合本轮任务与全部后续补充继续处理，从尚未完成的步骤开始，不要重复已完成的工具调用。";
    private static final String TASK = "[本轮任务] ", SUPPLEMENT = "[本轮补充] ";
    private static final String PAST_TASK = "[历史任务] ", PAST_SUPPLEMENT = "[历史补充] ";
    private static final int MAX_SUMMARIES = 8;
    private static final int SOFT_PERCENT = 90;

    /** throughTurn 为摘要覆盖的最后一个已完成轮次。 */
    record Checkpoint(int throughTurn, String summary) {}
    /** 在同一轮中从 firstSeq 开始的完整消息组。 */
    record Group(int turn, int firstSeq, List<Message> messages) {
        Group { messages = List.copyOf(messages); }
    }
    /** 已完成片段或用户边界；压缩片段保留原始末尾位置。 */
    private record Piece(Group group, int lastSeq, boolean summary) {
        boolean user() { return Message.USER.equals(group.messages().getFirst().role()); }
    }

    static final class ChunkTooLarge extends RuntimeException {}

    interface Summarizer {
        String summarize(String previous, List<Group> groups, boolean inRun);
    }

    /** 仅成功提交 Run 时才可持久化跨轮检查点。 */
    private Checkpoint checkpoint;
    /** 尚未并入跨轮摘要的成功轮次和本轮有序片段。 */
    private final Deque<Group> history;
    private final List<Piece> current = new ArrayList<>();
    /** 本轮最近一个完整工具批次；追加补充消息不改变其归属。 */
    private Group latestBatch;
    private final int turn;
    /** 优先保护的最近历史轮数和单条工具预览上限。 */
    private final int recent;
    private final int previewChars;
    /** 工具原文先持久化，再把返回的路径写入模型视图。 */
    private final Function<String, String> archiveOutput;
    private static final int MINOR_PERCENT = 75, KEEP_TOOL_RESULTS = 5;
    private boolean historicalSummaryFailed;
    private boolean runSummaryFailed;
    private int summaries;

    ConversationContext(Checkpoint checkpoint, List<Group> history, int turn, Message user, int recent, int previewChars,
                        Function<String, String> archiveOutput) {
        this.archiveOutput = archiveOutput;
        this.checkpoint = checkpoint;
        this.history = new ArrayDeque<>(history);
        this.turn = turn;
        appendUser(0, user);
        this.recent = recent;
        this.previewChars = previewChars;
    }

    Checkpoint checkpoint() { return checkpoint; }
    int turn() { return turn; }

    void appendBatch(int firstSeq, List<Message> batch) {
        append(firstSeq, batch);
        latestBatch = current.getLast().group();
    }

    void appendUser(int seq, Message message) {
        append(seq, List.of(message));
    }

    void appendReply(int seq, Message message) {
        append(seq, List.of(message));
    }

    private void append(int firstSeq, List<Message> messages) {
        if (firstSeq != (current.isEmpty() ? 0 : current.getLast().lastSeq() + 1) || messages.isEmpty()) {
            throw new IllegalStateException("本轮消息顺序不连续");
        }
        current.add(new Piece(new Group(turn, firstSeq, messages), firstSeq + messages.size() - 1, false));
    }

    List<Message> prepare(int budget, ToIntFunction<List<Message>> size, Summarizer summarize) {
        // 历史较早轮次及当前轮工具结果使用有界投影，保留协议配对。
        var view = view(false, latestBatch, 0);
        if (size.applyAsInt(view) < (long) budget * MINOR_PERCENT / 100) return view;
        // 轮中轻量归档保护最新五个工具输出；已吃紧时再按整批和硬预算回退。
        int protectFrom = Integer.MAX_VALUE, seen = 0;
        for (var piece : current.reversed()) {
            var group = piece.group();
            for (int i = group.messages().size() - 1; i >= 0 && seen < KEEP_TOOL_RESULTS; i--) {
                if (Message.TOOL.equals(group.messages().get(i).role())) {
                    protectFrom = group.firstSeq() + i;
                    seen++;
                }
            }
            if (seen == KEEP_TOOL_RESULTS) break;
        }
        view = view(false, latestBatch, protectFrom);
        int soft = (int) (budget * (SOFT_PERCENT / 100.0));
        if (size.applyAsInt(view) < soft) return view;

        // 优先缩短较早的结果；预留回答空间后仍能放下整批时，直接交给模型。
        if (latestBatch != null) {
            view = view(true, latestBatch, Integer.MAX_VALUE);
            if (size.applyAsInt(view) <= budget) return view;
        }
        view = view(true, null, Integer.MAX_VALUE);

        // 超过软阈值时优先处理旧轮次；超出硬上限时也可折叠受保护的近轮。
        while (!historicalSummaryFailed && summaries < MAX_SUMMARIES
                && size.applyAsInt(view) >= soft
                && history.size() > (size.applyAsInt(view) > budget ? 0 : recent)) {
            int eligible = history.size() - (size.applyAsInt(view) > budget ? 0 : recent);
            try {
                summarizeHistory(summarize, Math.min(eligible, 4));
                view = view(true, null, Integer.MAX_VALUE);
            } catch (RuntimeException failure) {
                historicalSummaryFailed = true;
            }
        }
        // 只折叠两个真实用户输入之间的执行片段，摘要不移动到下一条补充之后。
        while (!runSummaryFailed && summaries < MAX_SUMMARIES && size.applyAsInt(view) >= soft) {
            int at = -1;
            for (int i = 0; i < current.size(); i++) {
                if (!current.get(i).user() && !current.get(i).summary()) {
                    at = i;
                    break;
                }
            }
            if (at < 0) break;
            var piece = current.get(at);
            // 当最近批次的预览已满足硬预算，保留其协议配对与回查位置。
            if (piece.group() == latestBatch && size.applyAsInt(view) <= budget) break;
            var previous = current.get(at - 1);
            boolean combine = previous.summary();
            int first = combine ? previous.group().firstSeq() : piece.group().firstSeq();
            try {
                summaries++;
                String result = summarize(summarize, combine ? previous.group().messages().getFirst().content() : "",
                        List.of(project(piece.group())), true);
                var message = Message.assistant(WORK + result, List.of());
                current.set(at, new Piece(new Group(turn, first, List.of(message)), piece.lastSeq(), true));
                if (piece.group() == latestBatch) latestBatch = null;
                if (combine) current.remove(at - 1);
                view = view(true, null, Integer.MAX_VALUE);
            } catch (RuntimeException failure) {
                runSummaryFailed = true;
            }
        }
        // 独立硬校验：失败不能发送超预算请求，也不删除原消息。
        if (size.applyAsInt(view) > budget) throw new IllegalStateException(CAPACITY);
        // 压缩较早内容后可能重新放得下最近批次，再用最终请求的预算核验一次。
        if (latestBatch != null && summaries > 0) {
            var full = view(true, latestBatch, Integer.MAX_VALUE);
            if (size.applyAsInt(full) <= budget) return full;
        }
        return view;
    }

    private void summarizeHistory(Summarizer summarizer, int count) {
        // 摘要请求若自身超限，先缩小完整轮次分段，不向模型发送超预算摘要。
        for (int limit = count; limit > 0; limit /= 2) {
            var chunk = history.stream().limit(limit).map(this::project).toList();
            try {
                String next = summarize(summarizer, checkpoint == null ? "" : checkpoint.summary(), chunk, false);
                summaries++;
                checkpoint = new Checkpoint(chunk.getLast().turn(), next);
                chunk.forEach(ignored -> history.removeFirst());
                return;
            } catch (ChunkTooLarge oversized) {
                if (limit == 1) throw oversized;
            } catch (RuntimeException failed) {
                summaries++;
                throw failed;
            }
        }
    }

    private static String summarize(Summarizer summarize, String previous, List<Group> chunk, boolean inRun) {
        String summary = summarize.summarize(previous, chunk, inRun);
        if (summary == null || summary.isBlank()) throw new IllegalStateException(CAPACITY);
        return summary;
    }

    private List<Message> view(boolean compactRecent, Group fullBatch, int protectFrom) {
        var output = new ArrayList<Message>();
        if (checkpoint != null) output.add(Message.assistant(CHECKPOINT + checkpoint.summary(), List.of()));
        int protectedFrom = Math.max(0, history.size() - recent);
        int index = 0;
        for (var group : history) {
            addModelGroup(output, compactRecent || index++ < protectedFrom ? project(group) : group, true);
        }
        for (var piece : current) {
            var group = piece.group();
            addModelGroup(output, piece.summary() || group == fullBatch || group.firstSeq() >= protectFrom ? group : project(group, protectFrom), false);
        }
        if (current.getLast().summary()) output.add(Message.user(RESUME));
        return List.copyOf(output);
    }

    private static void addModelGroup(List<Message> output, Group group, boolean historical) {
        for (int i = 0; i < group.messages().size(); i++) {
            var message = group.messages().get(i);
            if (Message.USER.equals(message.role())) {
                var first = group.firstSeq() + i == 0;
                var tag = historical ? (first ? PAST_TASK : PAST_SUPPLEMENT) : (first ? TASK : SUPPLEMENT);
                message = Message.user(tag + message.content());
            }
            output.add(message);
        }
    }

    private Group project(Group group) {
        return project(group, Integer.MAX_VALUE);
    }

    private Group project(Group group, int protectFrom) {
        var projected = new ArrayList<Message>();
        var recallIds = new HashSet<String>();
        for (int i = 0; i < group.messages().size(); i++) {
            var m = group.messages().get(i);
            // 回查工具已经分页，必须完整交给模型，否则可能丢掉翻页游标。
            if (Message.ASSISTANT.equals(m.role())) {
                recallIds.clear();
                m.calls().stream().filter(call -> ConversationArchive.NAME.equals(call.name())
                                || MemoryMutationService.isRead(call.name()) || MemoryMutationService.isWrite(call.name()))
                        .forEach(call -> recallIds.add(call.id()));
            }
            if (Message.TOOL.equals(m.role()) && group.firstSeq() + i < protectFrom && !recallIds.contains(m.toolCallId())) {
                int chars = Boolean.FALSE.equals(m.success()) ? previewChars * 2 : previewChars;
                // 路径含 pg: 和 64 位摘要；只有标记比原文短才归档。
                if (m.content().length() > chars + ConversationArchive.MARKER_OVERHEAD) {
                    var preview = prefix(m.content(), chars).replace('\n', ' ').replace('\r', ' ');
                    var marker = ConversationArchive.MARKER + archiveOutput.apply(m.content()) + ConversationArchive.PREVIEW + preview;
                    m = new Message(m.role(), marker, m.calls(), m.toolCallId(), m.success());
                }
            }
            projected.add(m);
        }
        return new Group(group.turn(), group.firstSeq(), projected);
    }

    /** 不在 UTF-16 代理对之间截断。 */
    static String prefix(String text, int units) {
        int end = Math.min(text.length(), units);
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
}
