package com.pi.agent.model.content;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base interface for message content blocks.
 * Supports text, images, and tool calls using Java 21 sealed interfaces.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TextContent.class, name = "text"),
    @JsonSubTypes.Type(value = ImageContent.class, name = "image"),
    @JsonSubTypes.Type(value = ToolCallContent.class, name = "toolCall")
})
public sealed interface Content permits TextContent, ImageContent, ToolCallContent {
}
