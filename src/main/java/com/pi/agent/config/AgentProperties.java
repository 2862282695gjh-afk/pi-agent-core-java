package com.pi.agent.config;

import com.pi.agent.model.ThinkingLevel;
import com.pi.agent.model.ToolExecutionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot configuration properties for Agent.
 * 
 * Prefix: pi.agent
 * 
 * Example application.yml:
 * <pre>
 * pi:
 *   agent:
 *     model:
 *       id: gpt-4
 *       provider: openai
 *     system-prompt: "You are a helpful assistant."
 *     thinking-level: medium
 *     tool-execution: parallel
 *     max-retry-delay: 30s
 *     transport: sse
 *     llm:
 *       api-key: ${OPENAI_API_KEY}
 *       base-url: https://api.openai.com
 *       retry:
 *         max-retries: 3
 *         initial-backoff: 1s
 *         max-backoff: 30s
 *       rate-limit:
 *         max-concurrent-requests: 10
 *         queue-size: 100
 *       circuit-breaker:
 *         failure-threshold: 5
 *         recovery-timeout: 30s
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "pi.agent")
public record AgentProperties(
    ModelConfig model,
    String systemPrompt,
    ThinkingLevel thinkingLevel,
    ToolExecutionMode toolExecution,
    Duration maxRetryDelay,
    String transport,
    SteeringConfig steering,
    FollowUpConfig followUp,
    List<ToolConfig> tools,
    LlmConfig llm
) {
    
    public AgentProperties {
        // Default values
        if (thinkingLevel == null) {
            thinkingLevel = ThinkingLevel.OFF;
        }
        if (toolExecution == null) {
            toolExecution = ToolExecutionMode.PARALLEL;
        }
        if (transport == null) {
            transport = "sse";
        }
        if (steering == null) {
            steering = new SteeringConfig("one_at_a_time", List.of());
        }
        if (followUp == null) {
            followUp = new FollowUpConfig("one_at_a_time", List.of());
        }
        if (tools == null) {
            tools = new ArrayList<>();
        }
        if (llm == null) {
            llm = new LlmConfig(null, null, null, null, null);
        }
    }
    
    /**
     * Model configuration.
     * @param id Model identifier (e.g., "gpt-4", "claude-3-opus")
     * @param provider Provider name (e.g., "openai", "anthropic")
     * @param apiKey API key (optional, can be set via environment)
     * @param baseUrl Base URL for API (optional, for custom endpoints)
     */
    public record ModelConfig(
        @NotBlank(message = "Model id is required")
        String id,
        String provider,
        String apiKey,
        String baseUrl
    ) {}
    
    /**
     * Steering message configuration.
     * @param mode "all" or "one_at_a_time"
     * @param messages Pre-configured steering messages
     */
    public record SteeringConfig(
        String mode,
        List<String> messages
    ) {
        public SteeringConfig {
            if (mode == null) {
                mode = "one_at_a_time";
            }
            if (messages == null) {
                messages = new ArrayList<>();
            }
        }
    }
    
    /**
     * Follow-up message configuration.
     * @param mode "all" or "one_at_a_time"
     * @param messages Pre-configured follow-up messages
     */
    public record FollowUpConfig(
        String mode,
        List<String> messages
    ) {
        public FollowUpConfig {
            if (mode == null) {
                mode = "one_at_a_time";
            }
            if (messages == null) {
                messages = new ArrayList<>();
            }
        }
    }
    
    /**
     * Tool configuration for declarative tool registration.
     * @param name Tool name
     * @param description Tool description
     * @param enabled Whether the tool is enabled
     */
    public record ToolConfig(
        @NotBlank(message = "Tool name is required")
        String name,
        String description,
        boolean enabled
    ) {
        public ToolConfig {
            if (description == null) {
                description = "";
            }
        }
    }

    /**
     * LLM client configuration for resilience features.
     * @param apiKey API key for LLM provider
     * @param baseUrl Base URL for API endpoint
     * @param retry Retry configuration
     * @param rateLimit Rate limiting configuration
     * @param circuitBreaker Circuit breaker configuration
     */
    public record LlmConfig(
        String apiKey,
        String baseUrl,
        RetryConfig retry,
        RateLimitConfig rateLimit,
        CircuitBreakerConfig circuitBreaker
    ) {
        public LlmConfig {
            if (retry == null) {
                retry = new RetryConfig(3, Duration.ofSeconds(1), Duration.ofSeconds(30), 2.0, true, true, true);
            }
            if (rateLimit == null) {
                rateLimit = new RateLimitConfig(10, 100);
            }
            if (circuitBreaker == null) {
                circuitBreaker = new CircuitBreakerConfig(5, 3, Duration.ofSeconds(30));
            }
        }

        /**
         * Retry configuration.
         */
        public record RetryConfig(
            int maxRetries,
            Duration initialBackoff,
            Duration maxBackoff,
            double backoffMultiplier,
            Boolean retryOnRateLimit,
            Boolean retryOnServerError,
            Boolean retryOnTimeout
        ) {}

        /**
         * Rate limiting configuration.
         */
        public record RateLimitConfig(
            int maxConcurrentRequests,
            int tokensPerBucket
        ) {}

        /**
         * Circuit breaker configuration.
         */
        public record CircuitBreakerConfig(
            int failureThreshold,
            int successThreshold,
            Duration recoveryTimeout
        ) {}
    }
}
