package com.datagent.persistence.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 由活动 Run 确定会话边界，归档的引用和正文在同一 SQL 中写入。 */
@Mapper
public interface ArchiveMapper {
    String SAVE = """
            WITH source AS (
                SELECT session_id, 'pg:' || encode(sha256(convert_to(#{body}, 'UTF8')), 'hex') AS path
                FROM agent_run WHERE id = #{runId} AND status = 'RUNNING'
            ), saved AS (
                INSERT INTO agent_context_archive(session_id, path, body)
                SELECT session_id, path, #{body} FROM source ON CONFLICT DO NOTHING
            ) SELECT path FROM source
            """;
    String HISTORY = """
            SELECT m.payload::text FROM agent_run active
            JOIN agent_run r ON r.session_id = active.session_id
            JOIN agent_message m ON m.run_id = r.id
            WHERE active.id = #{runId} AND active.status = 'RUNNING' AND r.turn_no <= #{throughTurn}
              AND (r.id = active.id OR (r.status = 'SUCCEEDED' AND r.turn_no < active.turn_no))
            ORDER BY r.turn_no, m.seq
            """;
    String PAGE = """
            SELECT substr(a.body, #{offset} + 1, #{length}) FROM agent_run r
            JOIN agent_context_archive a ON a.session_id = r.session_id
            WHERE r.id = #{runId} AND r.status = 'RUNNING' AND a.path = #{path}
              AND #{offset} <= char_length(a.body)
            """;

    @Select(value = SAVE, affectData = true)
    String save(@Param("runId") String runId, @Param("body") String body);

    @Select(HISTORY)
    List<String> messages(@Param("runId") String runId, @Param("throughTurn") int throughTurn);

    @Select(PAGE)
    String page(@Param("runId") String runId, @Param("path") String path,
                @Param("offset") int offset, @Param("length") int length);
}
