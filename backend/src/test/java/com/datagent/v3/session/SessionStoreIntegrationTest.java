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

    @Test void schemaContainsThreeBusinessTables() {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=current_schema() and table_name in ('agent_run','agent_session','agent_message')", Integer.class)).isEqualTo(3);
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
        assertThat(history).hasSize(21);
        assertThat(history.getFirst().content()).isEqualTo("question 1");
        assertThat(history.getLast().content()).isEqualTo("next");
        store.fail(next, "finished test");
    }
}
