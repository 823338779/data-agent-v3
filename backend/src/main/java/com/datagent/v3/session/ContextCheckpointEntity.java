package com.datagent.v3.session;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 已成功的会话内历史压缩检查点。 */
@Getter
@Setter
@TableName("agent_context_checkpoint")
class ContextCheckpointEntity {
    /** 会话主键。 */
    @TableId(type = IdType.INPUT)
    private String sessionId;
    /** 已纳入摘要的最后一个成功轮次。 */
    private int throughTurn;
    /** 与原消息分开存储的摘要。 */
    private String summary;
    /** 摘要协议版本。 */
    private int formatVersion;
    /** 提交时间。 */
    private Instant updatedAt;
}
