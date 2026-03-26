package com.pi.agent.model.message;

import com.pi.agent.model.StopReason;
import com.pi.agent.model.content.Content;
import com.pi.agent.model.content.ToolCallContent;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Assistant message with optional tool calls.
 */
public record AssistantMessage(
    @JsonProperty("role") String role,
    @JsonProperty("content") List<Content> content,
    @JsonProperty("api") String api,
    @JsonProperty("provider") String provider,
    @JsonProperty("model") String model,
    @JsonProperty("usage") Usage usage,
    @JsonProperty("stopReason") StopReason stopReason,
    @JsonProperty("errorMessage") String errorMessage,
    @JsonProperty("timestamp") long timestamp
) implements AgentMessage {
    
    public AssistantMessage(List<Content> content, String provider, String model) {
        this(
            "assistant",
            content,
            null,
            provider,
            model,
            Usage.empty(),
            StopReason.END_TURN,
            null,
            System.currentTimeMillis()
        );
    }
    
    public static AssistantMessage of(String text, String provider, String model) {
        return new AssistantMessage(
            List.of(new com.pi.agent.model.content.TextContent(text)),
            provider,
            model
        );
    }
    
    public boolean hasError() {
        return stopReason == StopReason.ERROR || stopReason == StopReason.ABORTED;
    }
    
    public boolean hasToolCalls() {
        return content.stream().anyMatch(c -> c instanceof ToolCallContent);
    }
    
    /**
     * Token usage information.
     */
    public record Usage(
        @JsonProperty("input") long input,
        @JsonProperty("output") long output,
        @JsonProperty("cacheRead") long cacheRead,
        @JsonProperty("cacheWrite") long cacheWrite,
        @JsonProperty("totalTokens") long totalTokens,
        @JsonProperty("cost") Cost cost
    ) {
        public static Usage empty() {
            return new Usage(0, 0, 0, 0, 0, Cost.empty());
        }
        
        public record Cost(
            @JsonProperty("input") double input,
            @JsonProperty("output") double output,
            @JsonProperty("cacheRead") double cacheRead,
            @JsonProperty("cacheWrite") double cacheWrite,
            @JsonProperty("total") double total
        ) {
            public static Cost empty() {
                return new Cost(0, 0, 0, 0, 0);
            }
        }
    }
}
