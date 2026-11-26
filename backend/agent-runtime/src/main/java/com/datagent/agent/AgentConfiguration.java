package com.datagent.agent;

import java.time.Clock;
import java.util.List;
import com.datagent.tool.AgentTool;
import com.datagent.tool.ToolRegistry;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
class AgentConfiguration {
    @Bean
    AgentDefinition agentDefinition(@Value("${agent.model.name:qwen3.5:9b}") String model) {
        return AgentDefinition.general(model);
    }

    /** 启动时用当前 Agent 的允许列表校验已注册工具。 */
    @Bean
    ToolRegistry toolRegistry(List<AgentTool> tools, AgentDefinition agent, ObjectMapper json) {
        return new ToolRegistry(tools, agent.tools(), json);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
