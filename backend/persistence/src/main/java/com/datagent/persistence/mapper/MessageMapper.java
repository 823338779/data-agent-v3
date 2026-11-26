package com.datagent.persistence.mapper;

import com.datagent.persistence.entity.MessageEntity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** agent_message 的 MyBatis-Plus 映射。 */
@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {
}
