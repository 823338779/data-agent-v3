package com.datagent.v3.session;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import com.datagent.v3.model.Message;

@SpringBootTest
class SessionStoreIntegrationTest extends PostgresTest {
    @Autowired SessionStore store;
    @Autowired JdbcTemplate jdbc;

    @Test void schemaContainsConversationTables() {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=current_schema() and table_name in ('agent_run','agent_session','agent_message','agent_context_checkpoint')", Integer.class)).isEqualTo(4);
    }

    @Test void onlyOneConcurrentSenderAcquiresSession() throws Exception {
        var id = store.createSession("general");
        var ready = new CyclicBarrier(2);
        Callable<Boolean> send = () -> {
            ready.await();
            try { store.beginRun(id, "hello"); return true; }
            catch (ResponseStatusException busy) {
                assertThat(busy.getStatusCode().value()).isEqualTo(409);
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
        assertThat(store.recall(active, 1, 0).content()).isEqualTo("仅统计已支付订单");

        var other = store.createSession("general");
        var otherRun = store.beginRun(other, "另一个会话");
        assertThat(store.recall(otherRun, 1, 0).content()).isEqualTo("另一个会话");
        assertThat(store.recall(otherRun, 2, 0)).isNull();
        store.fail(otherRun, "结束验证");
        store.recover();

        var resumed = store.beginRun(session, "服务重启后继续");
        assertThat(store.context(resumed).checkpoint().throughTurn()).isEqualTo(1);
        assertThat(store.recall(resumed, 3, 0)).isNull();
        store.fail(resumed, "结束验证");
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
