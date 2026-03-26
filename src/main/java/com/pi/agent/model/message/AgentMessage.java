package com.pi.agent.model.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base interface for all agent messages.
 * Extensible to support custom message types.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "role")
@JsonSubTypes({
    @JsonSubTypes.Type(value = UserMessage.class, name = "user"),
    @JsonSubTypes.Type(value = AssistantMessage.class, name = "assistant"),
    @JsonSubTypes.Type(value = ToolResultMessage.class, name = "toolResult")
})
public sealed interface AgentMessage permits UserMessage, AssistantMessage, ToolResultMessage {
    
    /**
     * Returns the role of this message.
     */
    String role();
    
    /**
     * Returns the timestamp of this message.
     */
    long timestamp();
}
