package com.datagent.persistence.service;

import com.datagent.persistence.entity.SessionEntity;
import com.datagent.persistence.mapper.SessionMapper;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/** 为 agent_session 提供类型化 IService 操作。 */
@Service
class SessionPersistence extends ServiceImpl<SessionMapper, SessionEntity> {
}
