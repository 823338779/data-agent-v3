package com.datagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 补充收件与实际会话消息的关联，不持久化派生状态。 */
@Getter
@Setter
@TableName("agent_run_steer")
public class SteerEntity extends BaseEntity {
    /** 目标 Run 行锁内分配的收件顺序。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 不可变的目标 Run。 */
    private String runId;
    /** 客户端重试使用的 UUID。 */
    private String clientMessageId;
    /** 发件人输入的原文。 */
    private String message;
    /** 首次纳入对话后的真实序号。 */
    private Integer messageSeq;
    /** 数据库记录的接收时间。 */
    private Instant receivedAt;
}
