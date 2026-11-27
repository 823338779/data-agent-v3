package com.datagent.persistence.convetors;

import com.datagent.persistence.entity.ContextCheckpointEntity;
import com.datagent.persistence.entity.RunEntity;

import com.datagent.model.Message;
import com.datagent.persistence.SessionStore.Checkpoint;
import com.datagent.persistence.SessionStore.RunView;
import com.datagent.persistence.SessionStore.SteerReceipt;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** 在持久层边界转换会话模型，新增目标字段时由编译器检查。 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SessionMapping {
    // 检查点的归属和格式版本由事务提供。
    // 审计字段由数据库在实际写入时生成，不从业务检查点复制。
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "isDeleted", ignore = true)
    ContextCheckpointEntity toEntity(Checkpoint checkpoint, String sessionId, int formatVersion);

    Checkpoint toCheckpoint(ContextCheckpointEntity entity);

    // 运行元数据取自实体，补充入口状态取自当前业务判断。
    @Mapping(target = "steerCloseReason", source = "steerCloseReason")
    RunView toView(RunEntity run, List<Message> messages, boolean canSteer, String steerCloseReason,
                   List<SteerReceipt> steers);
}
