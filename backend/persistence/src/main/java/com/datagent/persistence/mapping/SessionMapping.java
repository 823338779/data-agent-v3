package com.datagent.persistence.mapping;

import com.datagent.persistence.entity.ContextCheckpointEntity;
import com.datagent.persistence.entity.RunEntity;

import com.datagent.model.Message;
import com.datagent.session.SessionStore.Checkpoint;
import com.datagent.session.SessionStore.RunView;
import com.datagent.session.SessionStore.SteerReceipt;
import java.time.Instant;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** 在持久层边界转换会话模型，新增目标字段时由编译器检查。 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SessionMapping {
    // 检查点的归属、格式版本和写入时间由事务提供。
    ContextCheckpointEntity toEntity(Checkpoint checkpoint, String sessionId, int formatVersion, Instant updatedAt);

    Checkpoint toCheckpoint(ContextCheckpointEntity entity);

    // 运行元数据取自实体，补充入口状态取自当前业务判断。
    @Mapping(target = "steerCloseReason", source = "steerCloseReason")
    RunView toView(RunEntity run, List<Message> messages, boolean canSteer, String steerCloseReason,
                   List<SteerReceipt> steers);
}
