package com.datagent.persistence.service;

import com.datagent.persistence.entity.SteerEntity;
import com.datagent.persistence.mapper.SteerMapper;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/** 供会话事务读写补充收件。 */
@Service
class SteerPersistence extends ServiceImpl<SteerMapper, SteerEntity> {}
