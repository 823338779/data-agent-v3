package com.datagent.v3.session;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** agent_run 对应的持久化实体。 */
@Getter
@Setter
@TableName(value = "agent_run")
public class RunEntity {
    /** 运行 UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;
    /** 所属会话。 */
    private String sessionId;
    /** 会话内从一开始的轮次序号。 */
    private Integer turnNo;
    /** 运行当前状态。 */
    private String status;
    /** 仍在运行时，关闭补充入口的原因。 */
    private String steerCloseReason;
    /** 失败或中断原因。 */
    private String failure;
    /** 运行创建时间。 */
    private Instant startedAt;
    /** 运行结束时间。 */
    private Instant endedAt;
}
