package com.datagent.v3.agent;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
class AgentConfiguration {
    @Bean
    AgentDefinition agentDefinition(@Value("${agent.model.name:qwen3.5:9b}") String model) {
        return AgentDefinition.general(model);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
