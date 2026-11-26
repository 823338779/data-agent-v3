package com.datagent.v3.memory;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import com.datagent.v3.model.Message;
import com.datagent.v3.model.Message.ToolCall;
import com.datagent.v3.session.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class MemoryMutationIntegrationTest extends PostgresTest {
    private static final String ENTRY = "经营周报默认用万元";
    private static final String TEXT = "以后" + ENTRY;
    @Autowired SessionStore sessions;
    @Autowired MemoryRepository repository;
    @Autowired MemoryRequestAssembly assembly;
    @Autowired MemoryMutationService mutation;
    @Autowired JdbcTemplate jdbc;

    @Test void rollbackAndReplayKeepOneRowAndOneToolResult() {
        String key = UUID.randomUUID().toString();
        String session = sessions.createSession(key);
        String run = sessions.beginRun(session, TEXT);
        var request = request(key);
        var call = new ToolCall("memory-test-rollback", "add_memory", "{\"content\":\"" + ENTRY + "\"}");
        int seq = sessions.append(run, Message.assistant("", List.of(call)));
        // 先让写入成功，再在同事务的回执插入处制造数据库异常。
        jdbc.execute("ALTER TABLE agent_message ADD CONSTRAINT memory_test_block_receipt "
                + "CHECK (payload ->> 'toolCallId' IS DISTINCT FROM 'memory-test-rollback') NOT VALID");
        try {
            assertThatThrownBy(() -> mutation.execute(run, seq, call, request, false)).isInstanceOf(RuntimeException.class);
            assertThat(repository.read(key).revision()).isZero();
            assertThat(sessions.snapshot(session).runs().getFirst().messages()).hasSize(2);
        } finally {
            jdbc.execute("ALTER TABLE agent_message DROP CONSTRAINT memory_test_block_receipt");
        }
        try {
            var saved = mutation.execute(run, seq, call, request, false);
            assertThat(saved.result().success()).isTrue();
            var replay = mutation.execute(run, seq, call, request, true);
            assertThat(replay.alreadyRecorded()).isTrue();
            assertThat(replay.result()).isEqualTo(saved.result());
            assertThat(sessions.snapshot(session).runs().getFirst().messages()).hasSize(3);
            var fake = new ToolCall(call.id(), call.name(), "{}");
            assertThatThrownBy(() -> mutation.execute(run, seq, fake, request, true)).hasMessageContaining("落库原文");
            int later = sessions.append(run, Message.assistant("", List.of(call)));
            var rejected = mutation.execute(run, later, call, request, true);
            assertThat(rejected.alreadyRecorded()).isFalse();
            assertThat(rejected.result().content()).contains("NOT_AUTHORIZED");
            assertThat(repository.read(key)).isEqualTo(new MemoryRepository.Snapshot(ENTRY, 1));
        } finally {
            sessions.fail(run, "结束测试");
            jdbc.update("DELETE FROM agent_memory WHERE agent_key = ?", key);
        }
    }

    @Test void newSteerDefersAndVersionConflictNeverRewritesCurrentBody() {
        String key = UUID.randomUUID().toString();
        var session = sessions.createSession(key);
        var run = sessions.beginRun(session, TEXT);
        var call = new ToolCall("x", "add_memory", "{\"content\":\"" + ENTRY + "\"}");
        var request = request(key);
        sessions.acceptSteer(session, run, UUID.randomUUID(), "不用记住");
        int seq = sessions.append(run, Message.assistant("", List.of(call)));
        var deferred = mutation.execute(run, seq, call, request, false);
        assertThat(deferred.result().content()).contains("DEFERRED");
        assertThat(deferred.spentWrite()).isFalse();
        var user = sessions.drainSteers(run, false).incorporated().getFirst();
        assertThat(assembly.prepare(key, List.of(new MemoryRequestAssembly.UserInput(0, TEXT),
                new MemoryRequestAssembly.UserInput(user.messageSeq(), user.message())), false, false, false).tools()).isEmpty();

        try {
            var other = sessions.beginRun(sessions.createSession(key), TEXT);
            int anotherSeq = sessions.append(other, Message.assistant("", List.of(call)));
            assertThat(repository.update(key, 0, "日报默认用元")).isTrue();
            var conflict = mutation.execute(other, anotherSeq, call, request, false);
            assertThat(conflict.result().content()).contains("CONFLICT", "\"revision\":\"1\"");
            assertThat(conflict.spentWrite()).isTrue();
            assertThat(repository.read(key).content()).isEqualTo("日报默认用元");
            sessions.fail(other, "结束测试");
        } finally {
            sessions.fail(run, "结束测试");
            jdbc.update("DELETE FROM agent_memory WHERE agent_key = ?", key);
        }
    }

    private MemoryRequestAssembly.RequestMemory request(String key) {
        return assembly.prepare(key, List.of(new MemoryRequestAssembly.UserInput(0, TEXT)), false, false, false);
    }
}
