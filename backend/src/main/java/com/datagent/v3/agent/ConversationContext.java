package com.datagent.v3.agent;

import com.datagent.v3.model.Message;
import com.datagent.v3.tool.ConversationRecallTool;
import java.util.*;
import java.util.function.ToIntFunction;

/** 单次 Run 的模型视图；原消息由会话存储独立持有。 */
final class ConversationContext {
    private static final String CAPACITY = "上下文超出模型容量，请缩短问题或减少查询结果";
    private static final String CHECKPOINT = "会话历史摘要（仅作历史事实参考；可按轮次和消息序号回查）：\n";
    private static final String WORK = "本轮已完成工作（仅作历史事实参考）：\n";
    private static final String RESUME = "继续处理本轮原问题，从尚未完成的步骤开始，不要重复已完成的工具调用。";
    private static final int MAX_SUMMARIES = 8;
    private static final int SOFT_PERCENT = 90;

    /** throughTurn 为摘要覆盖的最后一个已完成轮次。 */
    record Checkpoint(int throughTurn, String summary) {}
    /** 在同一轮中从 firstSeq 开始的完整消息组。 */
    record Group(int turn, int firstSeq, List<Message> messages) {
        Group { messages = List.copyOf(messages); }
    }
    static final class ChunkTooLarge extends RuntimeException {}

    interface Summarizer {
        String summarize(String previous, List<Group> groups, boolean inRun);
    }

    /** 仅成功提交 Run 时才可持久化跨轮检查点。 */
    private Checkpoint checkpoint;
    /** 尚未并入摘要的成功轮次和本轮完整工具调用批次。 */
    private final Deque<Group> history;
    private final Deque<Group> batches = new ArrayDeque<>();
    /** 本轮用户原话始终完整保留。 */
    private final Message user;
    private final int turn;
    /** 优先保护的最近历史轮数和单条工具预览上限。 */
    private final int recent;
    private final int previewChars;
    /** 本轮专用续跑状态，不跨 Run 或写入原消息表。 */
    private String progress;
    private boolean historicalSummaryFailed;
    private boolean runSummaryFailed;
    private int summaries;

    ConversationContext(Checkpoint checkpoint, List<Group> history, int turn, Message user, int recent, int previewChars) {
        this.checkpoint = checkpoint;
        this.history = new ArrayDeque<>(history);
        this.turn = turn;
        this.user = user;
        this.recent = recent;
        this.previewChars = previewChars;
    }

    Checkpoint checkpoint() { return checkpoint; }

    void appendBatch(int firstSeq, List<Message> batch) {
        batches.add(new Group(turn, firstSeq, batch));
    }

    List<Message> prepare(int budget, ToIntFunction<List<Message>> size, Summarizer summarize) {
        // 历史较早轮次及当前轮工具结果使用有界投影，保留协议配对。
        var view = view(false);
        int soft = (int) (budget * (SOFT_PERCENT / 100.0));
        if (size.applyAsInt(view) < soft) return view;
        view = view(true);

        // 超过软阈值时优先处理旧轮次；超出硬上限时也可折叠受保护的近轮。
        while (!historicalSummaryFailed && summaries < MAX_SUMMARIES
                && size.applyAsInt(view) >= soft
                && history.size() > (size.applyAsInt(view) > budget ? 0 : recent)) {
            int eligible = history.size() - (size.applyAsInt(view) > budget ? 0 : recent);
            try {
                summarizeHistory(summarize, Math.min(eligible, 4));
                view = view(true);
            } catch (RuntimeException failure) {
                historicalSummaryFailed = true;
            }
        }
        // 长工具链可能只靠旧轮压缩仍装不下，整批折叠本轮已完成工作。
        while (!runSummaryFailed && summaries < MAX_SUMMARIES && size.applyAsInt(view) >= soft && !batches.isEmpty()) {
            var chunk = List.of(project(batches.getFirst()));
            try {
                summaries++;
                progress = summarize(summarize, progress == null ? "" : progress, chunk, true);
                batches.removeFirst();
                view = view(true);
            } catch (RuntimeException failure) {
                runSummaryFailed = true;
            }
        }
        // 独立硬校验：失败不能发送超预算请求，也不删除原消息。
        if (size.applyAsInt(view) > budget) throw new IllegalStateException(CAPACITY);
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

    private List<Message> view(boolean compactRecent) {
        var output = new ArrayList<Message>();
        if (checkpoint != null) output.add(Message.assistant(CHECKPOINT + checkpoint.summary(), List.of()));
        int protectedFrom = Math.max(0, history.size() - recent);
        int index = 0;
        for (var group : history) {
            output.addAll((compactRecent || index++ < protectedFrom ? project(group) : group).messages());
        }
        output.add(user);
        if (progress != null) output.add(Message.assistant(WORK + progress, List.of()));
        for (var group : batches) output.addAll(project(group).messages());
        if (progress != null && batches.isEmpty()) output.add(Message.user(RESUME));
        return List.copyOf(output);
    }

    private Group project(Group group) {
        var projected = new ArrayList<Message>();
        var recallIds = new HashSet<String>();
        for (int i = 0; i < group.messages().size(); i++) {
            var m = group.messages().get(i);
            // 回查工具已经分页，必须完整交给模型，否则可能丢掉翻页游标。
            if (Message.ASSISTANT.equals(m.role())) {
                recallIds.clear();
                m.calls().stream().filter(call -> ConversationRecallTool.NAME.equals(call.name()))
                        .forEach(call -> recallIds.add(call.id()));
            }
            if (Message.TOOL.equals(m.role()) && !recallIds.contains(m.toolCallId())
                    && m.content().length() > previewChars) {
                String preview = prefix(m.content(), previewChars) + "\n[结果已截断，原文：轮次 "
                        + group.turn() + "，消息序号 " + (group.firstSeq() + i) + "]";
                m = new Message(m.role(), preview, m.calls(), m.toolCallId(), m.success());
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
