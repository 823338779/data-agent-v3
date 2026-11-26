package com.datagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** agent_session 对应的持久化实体。 */
@Getter
@Setter
@TableName(value = "agent_session")
public class SessionEntity {
    /** 会话 UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;
    /** 绑定的 Agent 标识。 */
    private String agentKey;
    /** 正在占用会话的运行标识。 */
    private String activeRunId;
    /** 创建时间。 */
    private Instant createdAt;
    /** 最近一次状态更新时间。 */
    private Instant updatedAt;
}
