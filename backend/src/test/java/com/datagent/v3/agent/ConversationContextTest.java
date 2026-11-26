package com.datagent.v3.agent;

import static org.assertj.core.api.Assertions.*;
import com.datagent.v3.model.Message;
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
        var context = new ConversationContext(null, history, 13, Message.user(QUERY), 1, 48);
        var view = context.prepare(1500, ConversationContextTest::size, (previous, source, inRun) -> {
            assertThat(source).isNotEmpty();
            return PAID;
        });
        assertThat(view).extracting(Message::content).anyMatch(text -> text.contains(PAID)).contains(QUERY);
        assertThat(context.checkpoint().throughTurn()).isPositive();
        assertThat(history.getFirst().messages().getFirst().content()).isEqualTo(PAID);
    }

    @Test void summarizesCompletedToolBatchWithoutDroppingUserRequest() {
        var context = new ConversationContext(null, List.of(), 1, Message.user(QUERY), 1, 40);
        var call = new Message.ToolCall("call", "query", "x".repeat(320));
        context.appendBatch(1, List.of(Message.assistant("", List.of(call)), Message.result(call, "y".repeat(1000), true)));
        var view = context.prepare(330, ConversationContextTest::size, (previous, source, inRun) -> {
            assertThat(source.getFirst().messages()).extracting(Message::toolCallId).contains("call");
            return RECEIVED;
        });
        assertThat(view).extracting(Message::content).contains(QUERY).anyMatch(text -> text.contains(RECEIVED));
        assertThat(view).noneMatch(message -> Message.TOOL.equals(message.role()));
        assertThat(context.checkpoint()).isNull();
    }

    @Test void oversizedToolResultUsesBoundedPreviewAndKeepsProtocolPair() {
        var context = new ConversationContext(null, List.of(), 1, Message.user(QUERY), 4, 40);
        var call = new Message.ToolCall("big", "query", "{}");
        var full = Message.result(call, "y".repeat(8000), true);
        context.appendBatch(1, List.of(Message.assistant("", List.of(call)), full));
        var view = context.prepare(400, ConversationContextTest::size, (prior, input, inRun) -> {
            throw new AssertionError("轻量裁剪足够，不应请求摘要");
        });
        assertThat(view.get(1).calls()).containsExactly(call);
        assertThat(view.getLast().toolCallId()).isEqualTo("big");
        assertThat(view.getLast().content()).contains("消息序号 2").hasSizeLessThan(100);
        assertThat(full.content()).hasSize(8000);
    }

    @Test void summaryFailurePreservesCheckpointAndStillChecksCapacity() {
        var checkpoint = new ConversationContext.Checkpoint(1, PAID);
        var history = List.of(new ConversationContext.Group(2, 0, List.of(Message.user("x".repeat(200)))));
        var context = new ConversationContext(checkpoint, history, 3, Message.user(QUERY), 0, 20);
        assertThatThrownBy(() -> context.prepare(100, ConversationContextTest::size, (prior, input, inRun) -> {
            throw new IllegalStateException("摘要不可用");
        })).hasMessageContaining("上下文");
        assertThat(context.checkpoint()).isEqualTo(checkpoint);
    }

    private static int size(List<Message> messages) {
        return messages.stream().mapToInt(m -> m.content().length() + 10
                + m.calls().stream().mapToInt(c -> c.arguments().length()).sum()).sum();
    }
}
