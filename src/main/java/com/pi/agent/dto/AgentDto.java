package com.pi.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pi.agent.model.content.Content;
import com.pi.agent.model.content.ImageContent;
import com.pi.agent.model.content.TextContent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * DTOs for REST API requests and responses.
 */
public final class AgentDto {
    
    private AgentDto() {} // Prevent instantiation
    
    /**
     * Request to send a prompt to the agent.
     * @param text The text prompt
     * @param images Optional image URLs
     * @param sessionId Optional session ID for conversation continuity
     * @param stream Whether to stream the response (default true)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PromptRequest(
        @NotBlank(message = "Text prompt is required")
        String text,
        List<String> images,
        String sessionId,
        Boolean stream
    ) {
        public PromptRequest {
            if (stream == null) {
                stream = true;
            }
        }
        
        /**
         * Convert to Content list for Agent.
         */
        public List<Content> toContent() {
            List<Content> content = new ArrayList<>();
            content.add(new TextContent(text));
            if (images != null) {
                for (String url : images) {
                    content.add(new ImageContent(url, "image/jpeg", null));
                }
            }
            return content;
        }
    }
    
    /**
     * Request to send multiple messages to the agent.
     * @param messages The messages to send
     * @param sessionId Optional session ID
     * @param stream Whether to stream the response
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MultiMessageRequest(
        @NotNull(message = "Messages are required")
        List<MessageDto> messages,
        String sessionId,
        Boolean stream
    ) {
        public MultiMessageRequest {
            if (stream == null) {
                stream = true;
            }
        }
    }
    
    /**
     * Simple message representation.
     * @param role Message role (user, assistant, system)
     * @param content Message content (text or object)
     */
    public record MessageDto(
        @NotBlank String role,
        Object content
    ) {}
    
    /**
     * Response for agent status.
     * @param sessionId Session ID
     * @param streaming Whether agent is currently streaming
     * @param messageCount Number of messages in context
     * @param hasQueuedMessages Whether there are queued messages
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StatusResponse(
        String sessionId,
        boolean streaming,
        int messageCount,
        boolean hasQueuedMessages
    ) {}
    
    /**
     * Response for non-streaming prompt.
     * @param sessionId Session ID
     * @param messages The conversation messages
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PromptResponse(
        String sessionId,
        List<MessageDto> messages
    ) {}
    
    /**
     * Event data for SSE streaming.
     * @param type Event type
     * @param data Event payload
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventData(
        @NotBlank String type,
        Object data
    ) {}
    
    /**
     * Error response.
     * @param error Error message
     * @param code Error code (optional)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(
        @NotBlank String error,
        String code
    ) {}
}
