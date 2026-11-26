package com.datagent.persistence.mapper;

import com.datagent.persistence.entity.ContextCheckpointEntity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 上下文检查点的 MyBatis-Plus 映射。 */
@Mapper
public interface ContextCheckpointMapper extends BaseMapper<ContextCheckpointEntity> {}
