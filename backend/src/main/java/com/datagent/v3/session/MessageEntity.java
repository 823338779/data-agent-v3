package com.datagent.v3.session;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

/** agent_message 对应的持久化实体。 */
@Getter
@Setter
@TableName(value = "agent_message", autoResultMap = true)
public class MessageEntity {
    /** 消息代理主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属运行。 */
    private String runId;
    /** 运行内从零开始的消息位置。 */
    private Integer seq;
    /** 存储为 PostgreSQL JSONB 的完整消息。 */
    @TableField(typeHandler = JsonbTextHandler.class)
    private String payload;
}
