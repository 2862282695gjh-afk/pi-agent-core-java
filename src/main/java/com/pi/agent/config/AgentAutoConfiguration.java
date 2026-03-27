package com.pi.agent.config;

import com.pi.agent.Agent;
import com.pi.agent.llm.*;
import com.pi.agent.model.AgentState;
import com.pi.agent.model.AgentTool;
import com.pi.agent.model.ThinkingLevel;
import com.pi.agent.model.ToolExecutionMode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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

    /**
     * Creates LlmMetrics bean for monitoring (optional, requires Micrometer).
     * 
     * @param meterRegistry Micrometer registry (may be null)
     * @return LlmMetrics instance
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MeterRegistry.class)
    public LlmMetrics llmMetrics(MeterRegistry meterRegistry) {
        log.info("Configuring LlmMetrics with Micrometer");
        return new LlmMetrics(meterRegistry);
    }

    /**
     * Creates OpenAiClient bean for LLM communication.
     * 
     * @param properties Configuration properties
     * @return OpenAiClient instance
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "pi.agent.llm", name = "api-key")
    public OpenAiClient openAiClient(AgentProperties properties) {
        String baseUrl = properties.llm() != null && properties.llm().baseUrl() != null 
            ? properties.llm().baseUrl() 
            : "https://api.openai.com";
        String apiKey = properties.llm().apiKey();
        
        log.info("Configuring OpenAiClient with base URL: {}", baseUrl);
        return new OpenAiClient(baseUrl, apiKey);
    }

    /**
     * Creates ResilientOpenAiClient bean with full resilience features.
     * 
     * @param openAiClient Base OpenAI client
     * @param properties Configuration properties
     * @param llmMetrics Optional metrics (may be null)
     * @return ResilientOpenAiClient instance
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(OpenAiClient.class)
    public ResilientOpenAiClient resilientOpenAiClient(
            OpenAiClient openAiClient, 
            AgentProperties properties,
            LlmMetrics llmMetrics) {
        
        RetryConfig retryConfig = buildRetryConfig(properties);
        RateLimiter.RateLimitConfig rateLimitConfig = buildRateLimitConfig(properties);
        CircuitBreaker.CircuitBreakerConfig circuitBreakerConfig = buildCircuitBreakerConfig(properties);
        
        log.info("Configuring ResilientOpenAiClient with retry={}, rateLimit={}, circuitBreaker={}",
            retryConfig.maxRetries(),
            rateLimitConfig.maxConcurrentRequests(),
            circuitBreakerConfig.failureThreshold());
        
        return new ResilientOpenAiClient(
            openAiClient, 
            retryConfig, 
            rateLimitConfig, 
            circuitBreakerConfig,
            llmMetrics
        );
    }

    private RetryConfig buildRetryConfig(AgentProperties properties) {
        if (properties.llm() == null || properties.llm().retry() == null) {
            return new RetryConfig(); // defaults
        }
        
        AgentProperties.LlmConfig.RetryConfig retry = properties.llm().retry();
        return RetryConfig.builder()
            .maxRetries(retry.maxRetries() > 0 ? retry.maxRetries() : 3)
            .initialBackoff(retry.initialBackoff() != null ? retry.initialBackoff() : java.time.Duration.ofSeconds(1))
            .maxBackoff(retry.maxBackoff() != null ? retry.maxBackoff() : java.time.Duration.ofSeconds(30))
            .backoffMultiplier(retry.backoffMultiplier() > 0 ? retry.backoffMultiplier() : 2.0)
            .retryOnRateLimit(retry.retryOnRateLimit() != null ? retry.retryOnRateLimit() : true)
            .retryOnServerError(retry.retryOnServerError() != null ? retry.retryOnServerError() : true)
            .retryOnTimeout(retry.retryOnTimeout() != null ? retry.retryOnTimeout() : true)
            .build();
    }

    private RateLimiter.RateLimitConfig buildRateLimitConfig(AgentProperties properties) {
        if (properties.llm() == null || properties.llm().rateLimit() == null) {
            return RateLimiter.RateLimitConfig.builder().build(); // defaults
        }
        
        AgentProperties.LlmConfig.RateLimitConfig rateLimit = properties.llm().rateLimit();
        return RateLimiter.RateLimitConfig.builder()
            .maxConcurrentRequests(rateLimit.maxConcurrentRequests() > 0 ? rateLimit.maxConcurrentRequests() : 10)
            .build();
    }

    private CircuitBreaker.CircuitBreakerConfig buildCircuitBreakerConfig(AgentProperties properties) {
        if (properties.llm() == null || properties.llm().circuitBreaker() == null) {
            return CircuitBreaker.CircuitBreakerConfig.builder().build(); // defaults
        }
        
        AgentProperties.LlmConfig.CircuitBreakerConfig cb = properties.llm().circuitBreaker();
        return CircuitBreaker.CircuitBreakerConfig.builder()
            .failureThreshold(cb.failureThreshold() > 0 ? cb.failureThreshold() : 5)
            .halfOpenSuccessThreshold(cb.successThreshold() > 0 ? cb.successThreshold() : 2)
            .openDuration(cb.recoveryTimeout() != null ? cb.recoveryTimeout() : java.time.Duration.ofSeconds(30))
            .build();
    }
}
