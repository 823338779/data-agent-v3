package com.datagent.agent;

import static org.assertj.core.api.Assertions.*;
import com.datagent.model.Message;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConversationContextTest {
    private static final String PAID = "仅统计已支付订单";
    private static final String RECEIVED = "已完成订单查询";
    private static final String QUERY = "按地区拆开";

    @Test void summarizesOldTurnsWithoutMutatingOriginalHistory() {
        var history = new ArrayList<ConversationContext.Group>();
        for (int i = 1; i <= 12; i++) {
            history.add(new ConversationContext.Group(i, 0, List.of(Message.user(i == 1 ? PAID : "x".repeat(180)),
                    Message.assistant("y".repeat(180), List.of()))));
        }
        var context = new ConversationContext(null, history, 13, Message.user(QUERY), 1, 48, text -> "pg:test");
        var view = context.prepare(1500, ConversationContextTest::size, (previous, source, inRun) -> {
            assertThat(source).isNotEmpty();
            return PAID;
        });
        assertThat(view).extracting(Message::content).anyMatch(text -> text.contains(PAID)).contains("[本轮任务] " + QUERY);
        assertThat(context.checkpoint().throughTurn()).isPositive();
        assertThat(history.getFirst().messages().getFirst().content()).isEqualTo(PAID);
    }

    @Test void summarizesCompletedToolBatchWithoutDroppingUserRequest() {
        var context = new ConversationContext(null, List.of(), 1, Message.user(QUERY), 1, 40, text -> "pg:test");
        var call = new Message.ToolCall("call", "query", "x".repeat(320));
        context.appendBatch(1, List.of(Message.assistant("", List.of(call)), Message.result(call, "y".repeat(1000), true)));
        var view = context.prepare(330, ConversationContextTest::size, (previous, source, inRun) -> {
            assertThat(source.getFirst().messages()).extracting(Message::toolCallId).contains("call");
            return RECEIVED;
        });
        assertThat(view).extracting(Message::content).contains("[本轮任务] " + QUERY).anyMatch(text -> text.contains(RECEIVED));
        assertThat(view).noneMatch(message -> Message.TOOL.equals(message.role()));
        assertThat(context.checkpoint()).isNull();
    }

    @Test void retainsAllResultsOfLatestBatchAfterSteeringWhenTheyFit() {
        var context = new ConversationContext(null, List.of(), 1, Message.user(QUERY), 4, 40, text -> "pg:test");
        var old = new Message.ToolCall("old", "query", "{}");
        var first = new Message.ToolCall("first", "query", "{}");
        var second = new Message.ToolCall("second", "query", "{}");
        var a = Message.result(first, "a".repeat(600) + PAID, true);
        var b = Message.result(second, "b".repeat(600) + RECEIVED, true);
        context.appendBatch(1, List.of(Message.assistant("", List.of(old)), Message.result(old, "x".repeat(3000), true)));
        context.appendBatch(3, List.of(Message.assistant("", List.of(first, second)), a, b));
        context.appendUser(6, Message.user(QUERY));

        // 预算只够保留最近一批；两条结果和批次后的用户补充都应完整交付。
        var view = context.prepare(1450, ConversationContextTest::size, (prior, input, inRun) -> {
            throw new AssertionError("最近批次在预算内");
        });
        assertThat(view).containsSubsequence(a, b);
        assertThat(view.get(2).content()).contains("[DATUS_ARCHIVED] path=pg:test");
        assertThat(view.getLast().content()).contains(QUERY);
    }

    @Test void restoresLatestResultWhenSummarizingHistoryFreesEnoughSpace() {
        var history = List.of(new ConversationContext.Group(1, 0, List.of(Message.user("x".repeat(1300)))));
        var context = new ConversationContext(null, history, 2, Message.user(QUERY), 0, 40, text -> "pg:test");
        var call = new Message.ToolCall("latest", "query", "{}");
        var result = Message.result(call, "y".repeat(600) + RECEIVED, true);
        context.appendBatch(1, List.of(Message.assistant("", List.of(call)), result));

        // 只有旧历史迫使本次请求压缩，成功摘要释放的空间应还给最近一批。
        var view = context.prepare(900, ConversationContextTest::size, (prior, input, inRun) -> PAID);
        assertThat(context.checkpoint().throughTurn()).isEqualTo(1);
        assertThat(view.getLast()).isEqualTo(result);
    }

    @Test void oversizedToolResultUsesBoundedPreviewAndKeepsProtocolPair() {
        var context = new ConversationContext(null, List.of(), 1, Message.user(QUERY), 4, 40, text -> "pg:test");
        var call = new Message.ToolCall("big", "query", "{}");
        var full = Message.result(call, "y".repeat(8000), true);
        context.appendBatch(1, List.of(Message.assistant("", List.of(call)), full));
        var view = context.prepare(400, ConversationContextTest::size, (prior, input, inRun) -> {
            throw new AssertionError("轻量裁剪足够，不应请求摘要");
        });
        assertThat(view.get(1).calls()).containsExactly(call);
        assertThat(view.getLast().toolCallId()).isEqualTo("big");
        assertThat(view.getLast().content()).contains("[DATUS_ARCHIVED] path=pg:test").hasSizeLessThan(100);
        assertThat(full.content()).hasSize(8000);
    }

    @Test void summaryFailurePreservesCheckpointAndStillChecksCapacity() {
        var checkpoint = new ConversationContext.Checkpoint(1, PAID);
        var history = List.of(new ConversationContext.Group(2, 0, List.of(Message.user("x".repeat(200)))));
        var context = new ConversationContext(checkpoint, history, 3, Message.user(QUERY), 0, 20, text -> "pg:test");
        assertThatThrownBy(() -> context.prepare(100, ConversationContextTest::size, (prior, input, inRun) -> {
            throw new IllegalStateException("摘要不可用");
        })).hasMessageContaining("上下文");
        assertThat(context.checkpoint()).isEqualTo(checkpoint);
    }

    @Test void compactsBetweenUsersWithoutMovingOrDroppingThem() {
        var first = new Message.ToolCall("A", "query", "a".repeat(320));
        var second = new Message.ToolCall("B", "query", "b".repeat(320));
        var context = new ConversationContext(null, List.of(), 1, Message.user("[本轮补充] 字面标记"), 1, 40, text -> "pg:test");
        context.appendBatch(1, List.of(Message.assistant("", List.of(first)), Message.result(first, "甲", true)));
        context.appendUser(3, Message.user("  保留甲  "));
        context.appendBatch(4, List.of(Message.assistant("", List.of(second)), Message.result(second, "乙", true)));
        context.appendUser(6, Message.user("只用乙"));
        var view = context.prepare(220, ConversationContextTest::size, (previous, source, inRun) -> {
            assertThat(previous).isEmpty(); // 补充划开两段，后一次摘要不使用前一段。
            return source.getFirst().firstSeq() == 1 ? "摘要甲" : "摘要乙";
        });
        assertThat(view).extracting(Message::role).containsExactly("user", "assistant", "user", "assistant", "user");
        assertThat(view).extracting(Message::content).containsSequence("[本轮任务] [本轮补充] 字面标记",
                "本轮已完成工作（仅作历史事实参考）：\n摘要甲",
                "[本轮补充]   保留甲  ",
                "本轮已完成工作（仅作历史事实参考）：\n摘要乙", "[本轮补充] 只用乙");
    }

    @Test void keepsProtectedTextWhenOnlyUserMessagesExceedBudget() {
        var context = new ConversationContext(null, List.of(), 1, Message.user(QUERY), 1, 40, text -> "pg:test");
        context.appendUser(1, Message.user("原文".repeat(200)));
        assertThatThrownBy(() -> context.prepare(100, ConversationContextTest::size, (prior, source, inRun) -> {
            throw new AssertionError("不应摘要用户原文");
        })).hasMessageContaining("上下文");
        assertThat(context.prepare(600, ConversationContextTest::size, (a, b, c) -> "不调用"))
                .extracting(Message::content).contains("[本轮补充] " + "原文".repeat(200));
    }

    private static int size(List<Message> messages) {
        return messages.stream().mapToInt(m -> m.content().length() + 10
                + m.calls().stream().mapToInt(c -> c.arguments().length()).sum()).sum();
    }
}
