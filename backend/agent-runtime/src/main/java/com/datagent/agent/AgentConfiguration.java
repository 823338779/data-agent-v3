package com.datagent.agent;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
class AgentConfiguration {
    @Bean
    AgentDefinition agentDefinition(@Value("${agent.model.name}") String model) {
        return AgentDefinition.general(model);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
