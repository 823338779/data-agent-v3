package com.datagent.persistence.mapper;

import com.datagent.persistence.entity.MessageEntity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** agent_message 的 MyBatis-Plus 映射。 */
@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {
    // 消息序号用于引用和重放，逻辑删除后也不能再次分配。
    @Select("SELECT coalesce(max(seq), -1) + 1 FROM agent_message WHERE run_id = #{runId}")
    int nextSequence(String runId);
}
