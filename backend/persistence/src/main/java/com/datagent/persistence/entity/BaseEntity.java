package com.datagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import java.time.Instant;
import lombok.Data;

/** 所有业务实体共享的 PostgreSQL 行审计信息。 */
@Data
public abstract class BaseEntity {
    /** 所属租户，对应既有列 tenent_id；省略时由数据库填入固定租户。 */
    @TableField(value = "tenent_id", updateStrategy = FieldStrategy.NEVER)
    private Long tenantId;

    /** 数据库记录的最近一次写入时间。 */
    @TableField(value = "update_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;

    /** 首次写入时间，后续修改和恢复均保留原值。 */
    @TableField(value = "create_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    /** 当前逻辑删除发生的时间，活动记录为空。 */
    @TableField(value = "delete_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant deletedAt;

    /** 由逻辑删除操作设置，普通实体更新不能覆盖该状态。 */
    @TableLogic(value = "false", delval = "true")
    @TableField(value = "is_deleted", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Boolean isDeleted;
}
