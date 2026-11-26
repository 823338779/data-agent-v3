package com.datagent.v3.memory;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import com.datagent.v3.session.PostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class MemoryRepositoryIntegrationTest extends PostgresTest {
    @Autowired MemoryRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test void oneTextRowAndByteLimitAcrossFullReplacement() {
        var key = UUID.randomUUID().toString();
        try {
            assertThat(repository.read(key)).isEqualTo(new MemoryRepository.Snapshot("", 0));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_memory WHERE agent_key = ?", Integer.class, key)).isZero();
            var text = "字".repeat(666) + "\n\"";
            assertThat(repository.update(key, 0, text)).isTrue();
            assertThat(jdbc.queryForObject("SELECT pg_typeof(content)::text FROM agent_memory WHERE agent_key = ?", String.class, key))
                    .isEqualTo("text");
            assertThat(repository.read(key)).isEqualTo(new MemoryRepository.Snapshot(text, 1));
            assertThat(repository.read(key).remaining()).isZero();
            assertThatThrownBy(() -> repository.update(key, 1, text + "A")).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(repository.read(key).revision()).isEqualTo(1);
            assertThat(repository.update(key, 1, "")).isTrue();
            assertThat(repository.update(key, 0, text)).isFalse();
            assertThat(repository.read(key)).isEqualTo(new MemoryRepository.Snapshot("", 2));
        } finally {
            remove(key);
        }
    }

    @Test void onlyOneWriterCanUseTheSameRevision() throws Exception {
        var key = UUID.randomUUID().toString();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (long revision : List.of(0L, 1L)) {
                var gate = new CyclicBarrier(2);
                var first = workers.submit(() -> { gate.await(5, TimeUnit.SECONDS); return repository.update(key, revision, "甲"); });
                var second = workers.submit(() -> { gate.await(5, TimeUnit.SECONDS); return repository.update(key, revision, "乙"); });
                assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
                assertThat(repository.read(key).revision()).isEqualTo(revision + 1);
                assertThat(repository.read(key).content()).isIn("甲", "乙");
            }
        } finally {
            remove(key);
        }
    }

    private void remove(String key) { jdbc.update("DELETE FROM agent_memory WHERE agent_key = ?", key); }
}
