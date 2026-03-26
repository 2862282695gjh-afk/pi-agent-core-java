package com.pi.agent.model.content;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Tool call content block within an assistant message.
 */
public record ToolCallContent(
    @JsonProperty("type") String type,
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("arguments") Map<String, Object> arguments
) implements Content {
    
    public ToolCallContent(String id, String name, Map<String, Object> arguments) {
        this("toolCall", id, name, arguments);
    }
    
    public static ToolCallContent of(String id, String name, Map<String, Object> arguments) {
        return new ToolCallContent(id, name, arguments);
    }
}
