package com.datagent.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.datagent.persistence.MemoryRepository;
import com.datagent.persistence.entity.MemoryEntity;
import com.datagent.persistence.mapper.MemoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 读写长期记忆，加入调用方持有的记忆修改和会话回执事务。 */
@Repository
@RequiredArgsConstructor
class PostgresMemoryRepository implements MemoryRepository {
    private static final String FOR_UPDATE = "FOR UPDATE";
    /** 未写入记忆的 Agent 从空正文和版本零开始。 */
    private static final Snapshot EMPTY = new Snapshot("", 0);
    /** 记忆的普通读取、锁定和按版本写入。 */
    private final MemoryMapper mapper;

    @Override
    public Snapshot read(String key) { return snapshot(mapper.selectById(key)); }

    @Override
    public Snapshot locked(String key) {
        // 使用实体映射获取行锁，与普通读取采用同一逻辑删除条件。
        return snapshot(mapper.selectOne(Wrappers.<MemoryEntity>lambdaQuery()
                .eq(MemoryEntity::getAgentKey, key).last(FOR_UPDATE)));
    }

    private static Snapshot snapshot(MemoryEntity row) {
        return row == null ? EMPTY : new Snapshot(row.getContent(), row.getRevision());
    }

    @Override
    public boolean update(String key, long version, String content) {
        // 首次创建和带版本更新均以实际影响行数判断并发结果。
        return (version == 0 ? mapper.insertFirst(key, content) : mapper.replace(key, version, content)) == 1;
    }
}
