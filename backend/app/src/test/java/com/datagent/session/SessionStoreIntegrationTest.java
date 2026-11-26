package com.datagent.session;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.datagent.model.Message;

@SpringBootTest
class SessionStoreIntegrationTest extends PostgresTest {
    @Autowired SessionStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test void schemaContainsConversationTables() {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=current_schema() and table_name in ('agent_run','agent_session','agent_message','agent_context_checkpoint')", Integer.class)).isEqualTo(4);
    }

    @Test void onlyOneConcurrentSenderAcquiresSession() throws Exception {
        var id = store.createSession("general");
        var ready = new CyclicBarrier(2);
        Callable<Boolean> send = () -> {
            ready.await();
            try { store.beginRun(id, "hello"); return true; }
            catch (SessionException busy) {
                assertThat(busy.reason()).isEqualTo(SessionException.Reason.ACTIVE_RUN);
                return false;
            }
        };
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(send);
            var second = workers.submit(send);
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        var runs = store.snapshot(id).runs();
        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst().messages()).hasSize(1);
        store.fail(runs.getFirst().id(), "finished test");
    }

    @Test void checkpointNeverReplacesOriginalMessagesAndRecallIsSessionScoped() {
        var session = store.createSession("general");
        var previous = store.beginRun(session, "仅统计已支付订单");
        store.finish(previous, Message.assistant("确认口径", List.of()));

        var folded = store.beginRun(session, "继续");
        store.finishWithContext(folded, Message.assistant("已继续", List.of()),
                new SessionStore.Checkpoint(1, "已确认只统计已支付订单"));
        var active = store.beginRun(session, "回查");
        assertThat(store.context(active).checkpoint().throughTurn()).isEqualTo(1);
        assertThat(store.context(active).history()).extracting(SessionStore.RunView::turnNo).containsExactly(2);
        assertThat(store.snapshot(session).runs().getFirst().messages().getFirst().content()).isEqualTo("仅统计已支付订单");
        var path = store.saveArchive(active, "仅统计已支付订单");
        assertThat(store.archivePage(active, path, 0, 100)).isEqualTo("仅统计已支付订单");

        var other = store.createSession("general");
        var otherRun = store.beginRun(other, "另一个会话");
        assertThat(store.archiveMessages(otherRun, 1)).extracting(Message::content).containsExactly("另一个会话");
        assertThat(store.archivePage(otherRun, path, 0, 100)).isNull();
        store.fail(otherRun, "结束验证");
        store.recover();

        var resumed = store.beginRun(session, "服务重启后继续");
        assertThat(store.context(resumed).checkpoint().throughTurn()).isEqualTo(1);
        assertThat(store.archiveMessages(resumed, 3)).extracting(Message::content).doesNotContain("回查");
        assertThat(store.archivePage(resumed, path, 0, 100)).isEqualTo("仅统计已支付订单");
        store.fail(resumed, "结束验证");
    }

    @Test void acceptanceUsesRunOwnershipAndStableId() throws Exception {
        String session = store.createSession("general");
        String run = store.beginRun(session, "首问");
        var key = UUID.randomUUID();
        var ready = new CyclicBarrier(2);
        Callable<SessionStore.SteerAcceptance> submit = () -> {
            ready.await(5, TimeUnit.SECONDS);
            return store.acceptSteer(session, run, key, "  保留原文  ");
        };
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(submit);
            var second = workers.submit(submit);
            assertThat(List.of(first.get(5, TimeUnit.SECONDS).created(), second.get(5, TimeUnit.SECONDS).created()))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(store.snapshot(session).runs().getFirst().steers()).hasSize(1);
        assertThatThrownBy(() -> store.acceptSteer(store.createSession("general"), run, key, "  保留原文  "))
                .isInstanceOfSatisfying(SessionException.class,
                        wrong -> assertThat(wrong.reason()).isEqualTo(SessionException.Reason.NOT_FOUND));
        assertSteerCode(() -> store.acceptSteer(session, run, key, "更改"), SessionStore.ID_REUSED, null);
        assertThat(store.drainSteers(run, false).incorporated().getFirst().messageSeq()).isEqualTo(1);
        store.commitTextReply(run, Message.assistant("完成", List.of()), null);
        var again = store.acceptSteer(session, run, key, "  保留原文  ").receipt();
        assertThat(again.runStatus()).isEqualTo(SessionStore.SUCCEEDED);
        assertThat(again.message()).isEqualTo("  保留原文  ");
        assertThat(again.messageSeq()).isEqualTo(1);
        assertSteerCode(() -> store.acceptSteer(session, run, UUID.randomUUID(), "太晚"),
                SessionStore.STEER_CLOSED, SessionStore.TERMINAL);
    }

    @Test void rejectsUtf8LimitsWithoutChargingRetries() {
        var session = store.createSession("general");
        var run = store.beginRun(session, "首问");
        var key = UUID.randomUUID();
        var exact = "字".repeat(682) + "ab";
        assertThat(store.acceptSteer(session, run, key, exact).created()).isTrue();
        assertSteerCode(() -> store.acceptSteer(session, run, UUID.randomUUID(), exact + "c"),
                SessionStore.STEER_LIMIT, null);
        for (int i = 0; i < 3; i++) store.acceptSteer(session, run, UUID.randomUUID(), exact);
        assertSteerCode(() -> store.acceptSteer(session, run, UUID.randomUUID(), "a"), SessionStore.STEER_LIMIT, null);
        assertThat(store.acceptSteer(session, run, key, exact).created()).isFalse();
        store.fail(run, "结束");
        var byCount = store.beginRun(session, "首问");
        for (int i = 0; i < 8; i++) store.acceptSteer(session, byCount, UUID.randomUUID(), "a");
        assertSteerCode(() -> store.acceptSteer(session, byCount, UUID.randomUUID(), "a"), SessionStore.STEER_LIMIT, null);
        store.fail(byCount, "结束");
    }

    @Test void drainWritesInFifoAndRollsBackOnReceiptUpdateFailure() {
        String session = store.createSession("general");
        String run = store.beginRun(session, "首问");
        store.acceptSteer(session, run, UUID.randomUUID(), "一");
        store.acceptSteer(session, run, UUID.randomUUID(), "二");
        // 故障发生在第二条的回填，第一条及两条原文写入均须回滚。
        jdbc.execute("ALTER TABLE agent_run_steer ADD CONSTRAINT steer_test_fail_second CHECK (message <> '二' OR message_seq IS NULL) NOT VALID");
        try {
            assertThatThrownBy(() -> store.drainSteers(run, false)).isInstanceOf(RuntimeException.class);
            var unchanged = store.snapshot(session).runs().getFirst();
            assertThat(unchanged.messages()).hasSize(1);
            assertThat(unchanged.steers()).allMatch(row -> row.messageSeq() == null);
        } finally {
            jdbc.execute("ALTER TABLE agent_run_steer DROP CONSTRAINT steer_test_fail_second");
        }
        var drained = store.drainSteers(run, false).incorporated();
        assertThat(drained).extracting(SessionStore.SteerReceipt::message).containsExactly("一", "二");
        assertThat(drained).extracting(SessionStore.SteerReceipt::messageSeq).containsExactly(1, 2);
        store.recover();
        var ended = store.snapshot(session).runs().getFirst();
        assertThat(ended.steers()).extracting(SessionStore.SteerReceipt::messageSeq).containsExactly(1, 2);
    }

    @Test void closeAndLastDrainSerializeAgainstAcceptance() throws Exception {
        for (boolean limit : List.of(false, true)) {
            for (boolean receiveFirst : List.of(false, true)) {
                String session = store.createSession("general");
                String run = store.beginRun(session, "首问");
                Runnable receive = () -> store.acceptSteer(session, run, UUID.randomUUID(), "补充");
                Runnable boundary = () -> {
                    if (limit) {
                        assertThat(store.drainSteers(run, true).incorporated()).hasSize(receiveFirst ? 1 : 0);
                    } else {
                        assertThat(store.commitTextReply(run, Message.assistant("原回答", List.of()), null).terminal())
                                .isEqualTo(!receiveFirst);
                    }
                };
                var firstAction = receiveFirst ? receive : boundary;
                Runnable secondAction = receiveFirst ? boundary : () -> assertSteerCode(receive,
                        SessionStore.STEER_CLOSED, limit ? SessionStore.LIMIT : SessionStore.TERMINAL);
                // 外层事务故意延迟第一方提交，第二方只能在同一行锁后观察结果。
                var holding = new CountDownLatch(1);
                var attempting = new CountDownLatch(1);
                try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                    var first = workers.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                        firstAction.run();
                        holding.countDown();
                        await(attempting);
                    }));
                    var second = workers.submit(() -> {
                        await(holding);
                        attempting.countDown();
                        secondAction.run();
                    });
                    first.get(8, TimeUnit.SECONDS);
                    second.get(8, TimeUnit.SECONDS);
                }
                var view = store.snapshot(session).runs().getFirst();
                assertThat(view.steers()).hasSize(receiveFirst ? 1 : 0);
                if (limit || receiveFirst) {
                    assertThat(store.snapshot(session).activeRunId()).isEqualTo(run);
                    store.fail(run, "结束");
                }
            }
        }
    }

    @Test void recoverLeavesUnreadReceiptUnchanged() {
        String session = store.createSession("general");
        String run = store.beginRun(session, "首问");
        var key = UUID.randomUUID();
        store.acceptSteer(session, run, key, "尚未纳入的补充");
        store.recover();
        var recovered = store.acceptSteer(session, run, key, "尚未纳入的补充").receipt();
        assertThat(recovered.runStatus()).isEqualTo(SessionStore.FAILED);
        assertThat(recovered.messageSeq()).isNull();
        assertThat(store.snapshot(session).runs().getFirst().messages()).hasSize(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static void assertSteerCode(Runnable action, String code, String reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SessionStore.SteerRejected.class, error -> {
            assertThat(error.getCode()).isEqualTo(code);
            assertThat(error.getReason()).isEqualTo(reason);
        });
    }

    @Test void recoversInterruptedRunBeforeAcceptingRequests() {
        var id = store.createSession("general");
        for (int i = 0; i < 11; i++) {
            var run = store.beginRun(id, "question " + i);
            store.finish(run, Message.assistant("answer " + i, List.of()));
        }
        var broken = store.beginRun(id, "interrupted");
        store.append(broken, Message.assistant("", List.of(new Message.ToolCall("orphan", "current_time", "{}"))));
        store.recover();
        var snapshot = store.snapshot(id);
        assertThat(snapshot.activeRunId()).isNull();
        assertThat(snapshot.runs().getLast().status()).isEqualTo("FAILED");
        var next = store.beginRun(id, "next");
        var history = store.context(next);
        assertThat(history.history()).hasSize(11);
        assertThat(history.history().getFirst().messages().getFirst().content()).isEqualTo("question 0");
        assertThat(history.user().content()).isEqualTo("next");
        store.fail(next, "finished test");
    }
}
