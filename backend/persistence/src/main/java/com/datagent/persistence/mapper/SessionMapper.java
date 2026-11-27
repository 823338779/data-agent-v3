package com.datagent.persistence.mapper;

import com.datagent.persistence.entity.SessionEntity;

import com.datagent.persistence.SessionStore;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** agent_session 的 MyBatis-Plus 映射。 */
@Mapper
public interface SessionMapper extends BaseMapper<SessionEntity> {
    // 只取第一轮的用户消息作为短标题，兼容旧会话并过滤尚未提问的空会话。
    String RECENT_QUERY = """
            SELECT s.id, left(m.payload ->> 'content', 80) AS title, s.update_at AS updated_at
            FROM agent_session s
            JOIN agent_run r ON r.session_id = s.id AND r.turn_no = 1
            JOIN agent_message m ON m.run_id = r.id AND m.seq = 0
            WHERE s.agent_key = #{agentKey} AND NOT s.is_deleted AND NOT r.is_deleted AND NOT m.is_deleted
            ORDER BY s.update_at DESC, s.id DESC
            LIMIT 50
            """;

    @Select(RECENT_QUERY)
    List<SessionStore.RecentSession> recent(@Param("agentKey") String agentKey);
}
