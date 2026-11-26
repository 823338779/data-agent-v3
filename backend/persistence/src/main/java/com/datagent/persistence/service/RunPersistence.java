package com.datagent.persistence.service;

import com.datagent.persistence.entity.RunEntity;
import com.datagent.persistence.mapper.RunMapper;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/** 为 agent_run 提供类型化 IService 操作。 */
@Service
class RunPersistence extends ServiceImpl<RunMapper, RunEntity> {
}
