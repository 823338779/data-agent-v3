package com.datagent.persistence.mapper;

import com.datagent.persistence.entity.ContextCheckpointEntity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** 上下文检查点的 MyBatis-Plus 映射。 */
@Mapper
public interface ContextCheckpointMapper extends BaseMapper<ContextCheckpointEntity> {
    // 每个会话只有一个检查点，新摘要可以恢复已逻辑删除的旧行。
    @Insert("""
            INSERT INTO agent_context_checkpoint (session_id, through_turn, summary, format_version)
            VALUES (#{sessionId}, #{throughTurn}, #{summary}, #{formatVersion})
            ON CONFLICT (session_id) DO UPDATE SET through_turn = EXCLUDED.through_turn,
                summary = EXCLUDED.summary, format_version = EXCLUDED.format_version, is_deleted = false
            WHERE agent_context_checkpoint.is_deleted
            """)
    int insertOrRestore(ContextCheckpointEntity checkpoint);
}
