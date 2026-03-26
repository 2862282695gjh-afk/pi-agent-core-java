package com.pi.agent.model;

import com.pi.agent.model.content.Content;
import java.util.List;

/**
 * Result returned from tool execution.
 * 
 * @param <T> Type of details payload
 */
public record AgentToolResult<T>(
    List<Content> content,
    T details
) {
    
    public static <T> AgentToolResult<T> of(List<Content> content, T details) {
        return new AgentToolResult<>(content, details);
    }
    
    public static AgentToolResult<Void> ofContent(List<Content> content) {
        return new AgentToolResult<>(content, null);
    }
    
    public static <T> AgentToolResult<T> error(String errorMessage) {
        return new AgentToolResult<>(
            List.of(new com.pi.agent.model.content.TextContent(errorMessage)),
            null
        );
    }
}
