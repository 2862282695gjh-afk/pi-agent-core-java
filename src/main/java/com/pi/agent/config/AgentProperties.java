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
    List<ToolConfig> tools
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
}
