package com.datagent.v3.memory;

import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 单行单正文存储；缺行读作版本零，清空保留版本。 */
@Repository
class MemoryRepository {
    static final int LIMIT = 2000;
    private static final String FETCH = "SELECT content, revision FROM agent_memory WHERE agent_key = ?";
    private static final String LOCK = FETCH + " FOR UPDATE";
    private static final String INSERT = """
            INSERT INTO agent_memory (agent_key, content, revision) VALUES (?, ?, 1)
            ON CONFLICT (agent_key) DO NOTHING
            """;
    private static final String UPDATE = """
            UPDATE agent_memory SET content = ?, revision = revision + 1 WHERE agent_key = ? AND revision = ?
            """;
    /** 参与工具结果和记忆共用的 Spring 事务。 */
    private final JdbcTemplate jdbc;

    MemoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Snapshot(String content, long revision) {
        int remaining() { return LIMIT - bytes(content); }
    }

    Snapshot read(String key) { return select(FETCH, key); }
    Snapshot locked(String key) { return select(LOCK, key); }

    private Snapshot select(String query, String key) {
        return jdbc.query(query, (rs, i) -> new Snapshot(rs.getString(1), rs.getLong(2)), key)
                .stream().findFirst().orElseGet(() -> new Snapshot("", 0));
    }

    boolean update(String key, long version, String content) {
        return (version == 0 ? jdbc.update(INSERT, key, content) : jdbc.update(UPDATE, content, key, version)) == 1;
    }

    static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
}
