package com.datagent.v3.session;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/** 为 agent_message 提供类型化 IService 操作。 */
@Service
class MessagePersistence extends ServiceImpl<MessageMapper, MessageEntity> {
}
