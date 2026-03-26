package com.pi.agent.config;

import com.pi.agent.model.ModelInfo;
import com.pi.agent.service.Agent;
import com.pi.agent.service.AgentOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfiguration {
    
    @Value("${pi.agent.default-provider:google}")
    private String defaultProvider;
    
    @Value("${pi.agent.default-model:gemini-2.5-flash-lite-preview-06-17}")
    private String defaultModel;
    
    @Bean
    public Agent defaultAgent() {
        return new Agent(AgentOptions.builder()
            .model(ModelInfo.of(defaultProvider, defaultModel))
            .build());
    }
}
