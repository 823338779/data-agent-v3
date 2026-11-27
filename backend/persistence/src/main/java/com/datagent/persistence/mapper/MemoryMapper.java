package com.datagent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.persistence.entity.MemoryEntity;
import org.apache.ibatis.annotations.*;

/** 保存长期记忆时保留首次写入竞争和按版本更新的数据库语义。 */
@Mapper
public interface MemoryMapper extends BaseMapper<MemoryEntity> {
    String INSERT_FIRST = """
            INSERT INTO agent_memory (agent_key, content, revision) VALUES (#{key}, #{content}, 1)
            ON CONFLICT (agent_key) DO UPDATE SET content = EXCLUDED.content,
                revision = agent_memory.revision + 1, is_deleted = false WHERE agent_memory.is_deleted
            """;
    String REPLACE = """
            UPDATE agent_memory SET content = #{content}, revision = revision + 1
            WHERE agent_key = #{key} AND revision = #{version} AND NOT is_deleted
            """;

    @Insert(INSERT_FIRST)
    int insertFirst(@Param("key") String key, @Param("content") String content);

    @Update(REPLACE)
    int replace(@Param("key") String key, @Param("version") long version, @Param("content") String content);
}
