package com.datagent.v3.session;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/** 提供检查点的类型化持久化操作。 */
@Service
class ContextCheckpointPersistence extends ServiceImpl<ContextCheckpointMapper, ContextCheckpointEntity> {}
