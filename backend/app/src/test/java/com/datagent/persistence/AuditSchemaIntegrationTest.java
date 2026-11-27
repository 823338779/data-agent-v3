package com.datagent.persistence;

import com.datagent.session.PostgresTest;
import com.datagent.persistence.entity.MemoryEntity;
import com.datagent.persistence.mapper.MemoryMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AuditSchemaIntegrationTest extends PostgresTest {
    private static final String SCRIPT = "schema-postgresql.sql";
    private static final String AUDIT_COLUMNS = """
            SELECT count(*) FILTER (WHERE col_description(t.oid, a.attnum) IS NOT NULL AND
                (a.attname, a.atttypid, a.attnotnull) IN (
                    ('is_deleted', 'boolean'::regtype, true), ('tenent_id', 'bigint'::regtype, true),
                    ('create_at', 'timestamptz'::regtype, true), ('update_at', 'timestamptz'::regtype, true),
                    ('delete_at', 'timestamptz'::regtype, false)))
            FROM pg_class t JOIN pg_attribute a ON a.attrelid = t.oid AND NOT a.attisdropped
            WHERE t.relnamespace = current_schema()::regnamespace AND t.relkind = 'r'
            GROUP BY t.oid
            """;
    private static final String CREATE_SCHEMA = "CREATE SCHEMA audit_upgrade_test";
    private static final String USE_SCHEMA = "SET LOCAL search_path TO audit_upgrade_test";
    private static final String OLD_SESSION = """
            CREATE TABLE agent_session (id varchar(36) PRIMARY KEY, agent_key text NOT NULL,
                active_run_id varchar(36), created_at timestamptz NOT NULL DEFAULT now(),
                updated_at timestamptz NOT NULL DEFAULT now())
            """;
    private static final String INSERT_OLD = """
            INSERT INTO agent_session VALUES ('old', 'general', NULL, '2020-01-01 UTC', '2020-02-01 UTC')
            """;
    private static final String HISTORICAL = """
            SELECT create_at = '2020-01-01 UTC' AND update_at = '2020-02-01 UTC'
               AND NOT is_deleted AND delete_at IS NULL AND tenent_id = 1 FROM agent_session WHERE id = 'old'
            """;
    private static final String INSERT_MEMORY =
            "INSERT INTO agent_memory (agent_key, content, revision) VALUES ('audit-test', '正文', 1)";
    private static final String NEW_AUDIT = """
            SELECT create_at = update_at AND NOT is_deleted AND delete_at IS NULL AND tenent_id = 1
            FROM agent_memory WHERE agent_key = 'audit-test'
            """;
    private static final String MARK_DELETED = "UPDATE agent_session SET is_deleted = true WHERE id = 'old'";
    private static final String DELETE_STAMP = "SELECT delete_at FROM agent_session WHERE id = 'old'";
    private static final String DELETED = """
            SELECT create_at = '2020-01-01 UTC' AND update_at = delete_at AND update_at > '2020-02-01 UTC'
            FROM agent_session WHERE id = 'old'
            """;
    private static final String TOUCH = "UPDATE agent_session SET agent_key = 'updated' WHERE id = 'old'";
    private static final String RESTORE = "UPDATE agent_session SET is_deleted = false WHERE id = 'old'";
    private static final String KEY = "mapper-audit";
    private static final String CONTENT = "记忆正文";
    private static final String USAGE_UPDATED = """
            SELECT business_calls = 1 AND update_at > create_at AND tenent_id = 1
            FROM agent_run_usage WHERE run_id = ?
            """;
    private static final String DELETE_TIMES_MATCH = """
            SELECT is_deleted AND delete_at = update_at AND create_at < update_at
            FROM agent_memory WHERE agent_key = ?
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void directUsageWritesUpdateAuditTime(@Autowired SessionStore sessions) {
        // 统计没有实体，验证真实累计调用也能更新审计时间。
        String session = sessions.createSession(KEY);
        String run = sessions.beginRun(session, CONTENT);
        sessions.countModelCall(run, false);
        assertThat(jdbc.queryForObject(USAGE_UPDATED, Boolean.class, run)).isTrue();
    }

    @Test
    void entityAndCustomSqlShareOneAuditLifecycle(@Autowired MemoryMapper mapper) {
        // 通过实体写入时，以数据库实际写入时间为准，再读回继承的审计字段。
        MemoryEntity row = new MemoryEntity();
        row.setAgentKey(KEY);
        row.setContent(CONTENT);
        row.setRevision(1);
        row.setCreatedAt(Instant.EPOCH);
        row.setUpdatedAt(Instant.EPOCH);
        assertThat(mapper.insert(row)).isOne();
        MemoryEntity created = mapper.selectById(KEY);
        Instant createdAt = created.getCreatedAt();
        assertThat(createdAt).isAfter(Instant.EPOCH);
        assertThat(created.getUpdatedAt()).isEqualTo(createdAt);
        assertThat(created.getTenantId()).isEqualTo(1L);
        assertThat(created.getIsDeleted()).isFalse();
        assertThat(created.getDeletedAt()).isNull();

        // 全量实体更新不能改写租户、创建时间、删除状态或回填旧时间戳。
        created.setTenantId(99L);
        created.setIsDeleted(true);
        created.setCreatedAt(Instant.EPOCH);
        created.setDeletedAt(Instant.EPOCH);
        assertThat(mapper.updateById(created)).isOne();
        MemoryEntity updated = mapper.selectById(KEY);
        assertThat(updated.getTenantId()).isEqualTo(1L);
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isAfter(createdAt);
        assertThat(updated.getDeletedAt()).isNull();

        // 自定义 CAS、通用删除和恢复共用触发器；重建后旧版本失效。
        assertThat(mapper.replace(KEY, 1, CONTENT)).isOne();
        assertThat(mapper.selectById(KEY).getUpdatedAt()).isAfter(updated.getUpdatedAt());
        assertThat(mapper.deleteById(KEY)).isOne();
        assertThat(mapper.selectById(KEY)).isNull();
        assertThat(jdbc.queryForObject(DELETE_TIMES_MATCH, Boolean.class, KEY)).isTrue();
        assertThat(mapper.replace(KEY, 2, CONTENT)).isZero();
        assertThat(mapper.insertFirst(KEY, CONTENT)).isOne();
        MemoryEntity restored = mapper.selectById(KEY);
        assertThat(restored.getCreatedAt()).isEqualTo(createdAt);
        assertThat(restored.getDeletedAt()).isNull();
        assertThat(restored.getIsDeleted()).isFalse();
        assertThat(restored.getRevision()).isEqualTo(3L);
    }

    @Test
    void everyBusinessTableHasTypedDocumentedAuditColumns() {
        // 从数据库目录枚举业务表，也能发现今后建表时遗漏的字段。
        assertThat(jdbc.queryForList(AUDIT_COLUMNS, Integer.class)).isNotEmpty().containsOnly(5);
        jdbc.update(INSERT_MEMORY);
        assertThat(jdbc.queryForObject(NEW_AUDIT, Boolean.class)).isTrue();
    }

    @Test
    void upgradePreservesHistoryAndDeletionTimeAcrossRestarts() throws IOException {
        // 在事务内隔离旧表，使用与应用启动完全相同的迁移脚本。
        jdbc.execute(CREATE_SCHEMA);
        jdbc.execute(USE_SCHEMA);
        jdbc.execute(OLD_SESSION);
        jdbc.update(INSERT_OLD);
        String schema = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(schema);
        assertThat(jdbc.queryForObject(HISTORICAL, Boolean.class)).isTrue();

        // 删除时记录一次时间；普通写入和重启不覆盖，恢复时清除。
        jdbc.update(MARK_DELETED);
        OffsetDateTime deletedAt = jdbc.queryForObject(DELETE_STAMP, OffsetDateTime.class);
        assertThat(deletedAt).isNotNull();
        assertThat(jdbc.queryForObject(DELETED, Boolean.class)).isTrue();
        jdbc.update(TOUCH);
        jdbc.execute(schema);
        assertThat(jdbc.queryForObject(DELETE_STAMP, OffsetDateTime.class)).isEqualTo(deletedAt);
        jdbc.update(RESTORE);
        assertThat(jdbc.queryForObject(DELETE_STAMP, OffsetDateTime.class)).isNull();
    }
}
