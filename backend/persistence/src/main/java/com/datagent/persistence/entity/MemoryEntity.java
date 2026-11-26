package com.datagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 当前 Agent 的长期记忆正文与并发更新版本。 */
@Getter
@Setter
@TableName("agent_memory")
public class MemoryEntity {
    /** 当前记忆所属 Agent 的业务标识。 */
    @TableId(type = IdType.INPUT)
    private String agentKey;
    /** 已生效的完整长期记忆正文。 */
    private String content;
    /** 每次正文变化后递增，用于拒绝基于旧版本的改写。 */
    private long revision;
}
