package com.pi.agent.model.message;

import com.pi.agent.model.content.Content;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Tool result message returned from tool execution.
 */
public record ToolResultMessage(
    @JsonProperty("role") String role,
    @JsonProperty("toolCallId") String toolCallId,
    @JsonProperty("toolName") String toolName,
    @JsonProperty("content") List<Content> content,
    @JsonProperty("details") Object details,
    @JsonProperty("isError") boolean isError,
    @JsonProperty("timestamp") long timestamp
) implements AgentMessage {
    
    public ToolResultMessage(String toolCallId, String toolName, List<Content> content, Object details, boolean isError) {
        this("toolResult", toolCallId, toolName, content, details, isError, System.currentTimeMillis());
    }
    
    public static ToolResultMessage success(String toolCallId, String toolName, List<Content> content, Object details) {
        return new ToolResultMessage(toolCallId, toolName, content, details, false);
    }
    
    public static ToolResultMessage error(String toolCallId, String toolName, String errorMessage) {
        return new ToolResultMessage(
            toolCallId,
            toolName,
            List.of(new com.pi.agent.model.content.TextContent(errorMessage)),
            null,
            true
        );
    }
}
