package com.datagent.memory;

import java.nio.charset.StandardCharsets;

/** 长期记忆的版本化读写接口；写入服务与会话回执共享事务。 */
public interface MemoryRepository {
    int LIMIT = 2000;

    record Snapshot(String content, long revision) {
        int remaining() { return LIMIT - bytes(content); }
    }

    Snapshot read(String key);
    Snapshot locked(String key);
    boolean update(String key, long version, String content);

    static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
}
