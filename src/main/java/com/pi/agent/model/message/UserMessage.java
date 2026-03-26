package com.pi.agent.model.message;

import com.pi.agent.model.content.Content;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * User message sent to the agent.
 */
public record UserMessage(
    @JsonProperty("role") String role,
    @JsonProperty("content") List<Content> content,
    @JsonProperty("timestamp") long timestamp
) implements AgentMessage {
    
    public UserMessage(List<Content> content) {
        this("user", content, System.currentTimeMillis());
    }
    
    public UserMessage(String text) {
        this("user", List.of(new com.pi.agent.model.content.TextContent(text)), System.currentTimeMillis());
    }
    
    public static UserMessage of(String text) {
        return new UserMessage(text);
    }
    
    public static UserMessage of(List<Content> content) {
        return new UserMessage(content);
    }
}
