package com.pi.agent.model.content;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Text content block.
 */
public record TextContent(
    @JsonProperty("type") String type,
    @JsonProperty("text") String text
) implements Content {
    
    public TextContent(String text) {
        this("text", text);
    }
    
    public static TextContent of(String text) {
        return new TextContent(text);
    }
}
