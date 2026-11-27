package com.datagent.memory;

import com.datagent.persistence.MemoryRepository;
import com.datagent.tool.MemoryTool;
import com.datagent.tool.execution.ToolExecution;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import com.datagent.model.Message;
import com.datagent.model.Message.ToolCall;
import com.datagent.persistence.SessionStore;
import com.datagent.session.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class MemoryMutationIntegrationTest extends PostgresTest {
    private static final String ENTRY = "经营周报默认用万元";
    private static final String TEXT = "以后" + ENTRY;
    private static final String ADD_ARGUMENTS = "{\"action\":\"add\",\"content\":\"" + ENTRY + "\"}";
    @Autowired
    SessionStore sessions;
    @Autowired MemoryRepository repository;
    @Autowired ToolExecution toolExecution;
    @Autowired JdbcTemplate jdbc;
    @Autowired tools.jackson.databind.ObjectMapper json;

    @Test void rollbackAndReplayKeepOneRowAndOneToolResult() {
        String key = UUID.randomUUID().toString();
        String session = sessions.createSession(key);
        String run = sessions.beginRun(session, TEXT);
        ToolCall call = new ToolCall("memory-test-rollback", MemoryTool.NAME, ADD_ARGUMENTS);
        int seq = sessions.append(run, Message.assistant("", List.of(call)));
        // 先让写入成功，再在同事务的回执插入处制造数据库异常。
        jdbc.execute("ALTER TABLE agent_message ADD CONSTRAINT memory_test_block_receipt "
                + "CHECK (payload ->> 'toolCallId' IS DISTINCT FROM 'memory-test-rollback') NOT VALID");
        try {
            assertThatThrownBy(() -> invoke(run, seq, call)).isInstanceOf(RuntimeException.class);
            assertThat(repository.read(key).revision()).isZero();
            assertThat(sessions.snapshot(session).runs().getFirst().messages()).hasSize(2);
        } finally {
            jdbc.execute("ALTER TABLE agent_message DROP CONSTRAINT memory_test_block_receipt");
        }
        try {
            Message saved = invoke(run, seq, call);
            assertThat(saved.success()).isTrue();
            Message replay = invoke(run, seq, call);
            assertThat(replay).isEqualTo(saved);
            assertThat(sessions.snapshot(session).runs().getFirst().messages()).hasSize(3);
            ToolCall fake = new ToolCall(call.id(), call.name(), argumentsForAdd("另一个条目"));
            assertThatThrownBy(() -> invoke(run, seq, fake)).hasMessageContaining("落库原文");
            int later = sessions.append(run, Message.assistant("", List.of(call)));
            Message repeated = invoke(run, later, call);
            assertThat(repeated.content()).contains("unchanged");
            assertThat(repository.read(key)).isEqualTo(new MemoryRepository.Snapshot(ENTRY, 1));
        } finally {
            sessions.fail(run, "结束测试");
            jdbc.update("DELETE FROM agent_memory WHERE agent_key = ?", key);
        }
    }

    @Test void newSteerDefersAndNextCallUsesLatestCommittedBody() {
        String key = UUID.randomUUID().toString();
        String session = sessions.createSession(key);
        String run = sessions.beginRun(session, TEXT);
        ToolCall call = new ToolCall("x", MemoryTool.NAME, ADD_ARGUMENTS);
        sessions.acceptSteer(session, run, UUID.randomUUID(), "不用记住");
        int seq = sessions.append(run, Message.assistant("", List.of(call)));
        Message deferred = invoke(run, seq, call);
        assertThat(deferred.content()).contains("DEFERRED");
        sessions.drainSteers(run, false);

        try {
            String other = sessions.beginRun(sessions.createSession(key), TEXT);
            int anotherSeq = sessions.append(other, Message.assistant("", List.of(call)));
            assertThat(repository.update(key, 0, "日报默认用元")).isTrue();
            Message result = invoke(other, anotherSeq, call);
            assertThat(result.success()).isTrue();
            assertThat(repository.read(key)).isEqualTo(new MemoryRepository.Snapshot("日报默认用元\n" + ENTRY, 2));
            sessions.fail(other, "结束测试");
        } finally {
            sessions.fail(run, "结束测试");
            jdbc.update("DELETE FROM agent_memory WHERE agent_key = ?", key);
        }
    }

    @Test void rejectsInvalidEntriesAndSupportsWholeLineEdits() {
        String key = UUID.randomUUID().toString();
        String run = sessions.beginRun(sessions.createSession(key), TEXT);
        try {
            // 有效操作由工具参数决定，目标、条目边界和存储容量由工具检查。
            String daily = "经营日报默认用元";
            String next = "经营周报默认用元";
            assertThat(invokeNew(run, ADD_ARGUMENTS).success()).isTrue();
            assertThat(invokeNew(run, argumentsForAdd(daily)).success()).isTrue();
            assertThat(invokeNew(run, argumentsForAdd(next)).content()).contains("CONFLICT");
            Message mismatch = invokeEdit(run, 2, ENTRY + "。", next);
            assertThat(mismatch.success()).isFalse();
            assertThat(mismatch.content()).contains("NO_MATCH");
            assertThat(json.readTree(mismatch.content()).path(MemoryTool.CONTENT).asString()).isEqualTo(ENTRY + "\n" + daily);
            assertThat(repository.read(key).revision()).isEqualTo(2);
            assertThat(invokeEdit(run, 2, ENTRY, next).success()).isTrue();
            assertThat(invokeEdit(run, 3, next, daily).content()).contains("CONFLICT");
            assertThat(invokeNew(run, argumentsForAdd("字".repeat(666))).content()).contains("FULL", "bytesNeeded");
            assertThat(invokeNew(run, argumentsForAdd("我偏好密码abc")).content()).contains("INVALID_ENTRY").doesNotContain("密码abc");
            assertThat(repository.read(key)).isEqualTo(new MemoryRepository.Snapshot(next + "\n" + daily, 3));
            assertThat(invokeEdit(run, 3, next, "").success()).isTrue();
            assertThat(repository.read(key)).isEqualTo(new MemoryRepository.Snapshot(daily, 4));
            // 用户只更正空格时，也应精确保存替换后的文本。
            assertThat(invokeEdit(run, 4, "默认用元", "默认 用元").content()).contains("updated");
            assertThat(repository.read(key)).isEqualTo(new MemoryRepository.Snapshot("经营日报默认 用元", 5));
        } finally {
            sessions.fail(run, "结束测试");
            jdbc.update("DELETE FROM agent_memory WHERE agent_key = ?", key);
        }
    }

    @Test void editsOnlyAnExactUniqueFragmentFromTheCurrentSnapshot() {
        String key = UUID.randomUUID().toString();
        String run = sessions.beginRun(sessions.createSession(key), TEXT);
        String daily = "日报/[草稿]+/每周五发2份，编号111。";
        String weekly = "📅周报：每周  ２　份。";
        try {
            assertThat(invokeNew(run, argumentsForAdd(daily)).success()).isTrue();
            assertThat(invokeNew(run, argumentsForAdd(weekly)).success()).isTrue();
            // 新工具拒绝位置参数；相似标点、空格数量、字符宽度和跨行片段均不能代替原文。
            assertThat(invokeNew(run, "{\"action\":\"edit\",\"start\":0,\"end\":1,\"new_string\":\"\"}", 2).success()).isFalse();
            for (String missing : List.of("", "周报:每周", "每周 ２", "每周  2", "２ 份", "。\n📅")) {
                Message failure = invokeEdit(run, 2, missing, "替换");
                assertThat(failure.success()).isFalse();
                assertThat(failure.content()).contains("NO_MATCH");
                assertThat(json.readTree(failure.content()).path("content").asString()).isEqualTo(daily + "\n" + weekly);
            }
            // 跨条目重复和同一行重叠匹配都不应任选一处修改。
            for (String repeated : List.of("每周", "11")) {
                Message failure = invokeEdit(run, 2, repeated, "替换");
                assertThat(failure.success()).isFalse();
                assertThat(failure.content()).contains("MULTIPLE_MATCHES");
            }
            Message stale = invokeEdit(run, 1, "周报：", "改");
            assertThat(stale.success()).isFalse();
            assertThat(json.readTree(stale.content()).path("code").asString()).isEqualTo("CONFLICT");
            assertThat(json.readTree(stale.content()).has("revision")).isFalse();
            assertThat(json.readTree(stale.content()).path("content").asString()).isEqualTo(daily + "\n" + weekly);
            assertThat(repository.read(key)).isEqualTo(new MemoryRepository.Snapshot(daily + "\n" + weekly, 2));

            // 复制完整标点和空格后唯一命中，替换内容及带正则符号的旧片段都按字面处理。
            Message edited = invokeEdit(run, 2, "周报：每周  ２　份", "周报：每周 $1\\份");
            String updated = "📅周报：每周 $1\\份。";
            assertThat(edited.success()).isTrue();
            assertThat(json.readTree(edited.content()).path("entry").asString()).isEqualTo(updated);
            assertThat(invokeEdit(run, 3, "/[草稿]+/", "").success()).isTrue();
            String shortened = "日报每周五发2份，编号111。";
            assertThat(repository.read(key)).isEqualTo(new MemoryRepository.Snapshot(shortened + "\n" + updated, 4));
            assertThat(invokeEdit(run, 4, updated, "").content()).contains("deleted");
            assertThat(repository.read(key)).isEqualTo(new MemoryRepository.Snapshot(shortened, 5));
        } finally {
            sessions.fail(run, "结束测试");
            jdbc.update("DELETE FROM agent_memory WHERE agent_key = ?", key);
        }
    }

    private Message invokeNew(String run, String arguments) {
        return invokeNew(run, arguments, 0);
    }

    private Message invokeNew(String run, String arguments, long requestRevision) {
        ToolCall call = new ToolCall(UUID.randomUUID().toString(), MemoryTool.NAME, arguments);
        int seq = sessions.append(run, Message.assistant("", List.of(call)));
        return toolExecution.invoke(run, seq, call, List.of(), requestRevision, () -> {});
    }

    private Message invoke(String run, int seq, ToolCall call) {
        return toolExecution.invoke(run, seq, call, List.of(), 0, () -> {});
    }

    private String argumentsForAdd(String entry) {
        return json.writeValueAsString(Map.of(MemoryTool.ACTION, MemoryTool.ADD, MemoryTool.CONTENT, entry));
    }

    private Message invokeEdit(String run, long requestRevision, String oldText, String next) {
        String arguments = json.writeValueAsString(Map.of(MemoryTool.ACTION, MemoryTool.EDIT,
                MemoryTool.OLD, oldText, MemoryTool.NEW, next));
        return invokeNew(run, arguments, requestRevision);
    }
}
