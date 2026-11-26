package com.datagent.persistence.mapper;

import com.datagent.persistence.entity.RunEntity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

/** agent_run 的 MyBatis-Plus 映射。 */
@Mapper
public interface RunMapper extends BaseMapper<RunEntity> {
    String INIT_USAGE = "INSERT INTO agent_run_usage(run_id) VALUES (#{runId})";
    String COUNT_BUSINESS = "UPDATE agent_run_usage SET business_calls = business_calls + 1 WHERE run_id = #{runId}";
    String COUNT_SUMMARY = "UPDATE agent_run_usage SET summary_calls = summary_calls + 1 WHERE run_id = #{runId}";
    String ADD_TOKENS = """
            <script>
            UPDATE agent_run_usage
            <set>
                <if test="input != null">
                    input_tokens = COALESCE(input_tokens, 0) + #{input}, input_reported_calls = input_reported_calls + 1,
                </if>
                <if test="output != null">
                    output_tokens = COALESCE(output_tokens, 0) + #{output}, output_reported_calls = output_reported_calls + 1,
                </if>
            </set>
            WHERE run_id = #{runId}
            </script>
            """;

    // 次数在调用开始前保存，实际用量只累加上报过的维度。
    @Insert(INIT_USAGE)
    void initUsage(String runId);
    @Update(COUNT_BUSINESS)
    void countBusiness(String runId);
    @Update(COUNT_SUMMARY)
    void countSummary(String runId);
    @Update(ADD_TOKENS)
    void addTokens(@Param("runId") String runId, @Param("input") Long input, @Param("output") Long output);
}
