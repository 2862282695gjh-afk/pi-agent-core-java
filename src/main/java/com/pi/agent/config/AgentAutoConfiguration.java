package com.pi.agent.config;

import com.pi.agent.Agent;
import com.pi.agent.model.AgentState;
import com.pi.agent.model.AgentTool;
import com.pi.agent.model.ThinkingLevel;
import com.pi.agent.model.ToolExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot Auto-Configuration for Agent.
 * 
 * Automatically configures an Agent bean when:
 * - pi.agent.model.id is set
 * - Agent class is on classpath
 * 
 * Can be disabled with: pi.agent.enabled=false
 */
@AutoConfiguration
@ConditionalOnClass(Agent.class)
@EnableConfigurationProperties(AgentProperties.class)
@ConditionalOnProperty(prefix = "pi.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentAutoConfiguration {
    
    private static final Logger log = LoggerFactory.getLogger(AgentAutoConfiguration.class);
    
    /**
     * Creates and configures the Agent bean.
     * 
     * @param properties Configuration properties
     * @return Configured Agent instance
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pi.agent.model", name = "id")
    public Agent agent(AgentProperties properties) {
        log.info("Configuring Agent with model: {}", properties.model().id());
        
        Agent.Builder builder = Agent.builder();
        
        // Configure model
        if (properties.model() != null) {
            AgentState.ModelInfo modelInfo = AgentState.ModelInfo.of(
                properties.model().provider() != null ? properties.model().provider() : "default",
                properties.model().id()
            );
            builder.model(modelInfo);
        }
        
        // Configure system prompt
        if (properties.systemPrompt() != null && !properties.systemPrompt().isBlank()) {
            builder.systemPrompt(properties.systemPrompt());
        }
        
        // Configure thinking level
        if (properties.thinkingLevel() != null) {
            builder.thinkingLevel(properties.thinkingLevel());
        }
        
        // Configure tool execution mode
        if (properties.toolExecution() != null) {
            builder.toolExecution(properties.toolExecution());
        }
        
        // Configure transport
        if (properties.transport() != null) {
            builder.transport(properties.transport());
        }
        
        // Configure max retry delay
        if (properties.maxRetryDelay() != null) {
            builder.maxRetryDelayMs(properties.maxRetryDelay().toMillis());
        }
        
        // Configure steering mode
        if (properties.steering() != null) {
            String mode = properties.steering().mode();
            if ("all".equalsIgnoreCase(mode)) {
                builder.steeringMode(Agent.SteeringMode.ALL);
            } else {
                builder.steeringMode(Agent.SteeringMode.ONE_AT_A_TIME);
            }
        }
        
        // Configure follow-up mode
        if (properties.followUp() != null) {
            String mode = properties.followUp().mode();
            if ("all".equalsIgnoreCase(mode)) {
                builder.followUpMode(Agent.FollowUpMode.ALL);
            } else {
                builder.followUpMode(Agent.FollowUpMode.ONE_AT_A_TIME);
            }
        }
        
        Agent agent = builder.build();
        
        log.info("Agent configured successfully");
        return agent;
    }
    
    /**
     * Creates AgentState bean for shared state management.
     * 
     * @param properties Configuration properties
     * @return AgentState instance
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentState agentState(AgentProperties properties) {
        AgentState state = new AgentState();
        
        if (properties.systemPrompt() != null && !properties.systemPrompt().isBlank()) {
            state = state.withSystemPrompt(properties.systemPrompt());
        }
        
        if (properties.thinkingLevel() != null) {
            state = state.withThinkingLevel(properties.thinkingLevel());
        }
        
        return state;
    }
}
