package com.pi.agent.model;

import com.pi.agent.model.content.Content;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Agent tool with execution capability.
 * Extends Tool with execute function and label.
 * 
 * @param <TDetails> Type of details payload returned by the tool
 */
public interface AgentTool<TDetails> extends Tool {
    
    /**
     * Human-readable label for display in UI.
     */
    String label();
    
    /**
     * Execute the tool.
     * 
     * @param toolCallId Tool call ID
     * @param params Validated parameters
     * @param onUpdate Callback for streaming updates (optional)
     * @return Tool result
     */
    AgentToolResult<TDetails> execute(
        String toolCallId,
        Map<String, Object> params,
        Consumer<AgentToolResult<TDetails>> onUpdate
    );
    
    /**
     * Create a simple tool with synchronous execution.
     */
    static <T> AgentTool<T> of(
        String name,
        String description,
        String label,
        JsonNode parameters,
        BiFunction<String, Map<String, Object>, AgentToolResult<T>> executor
    ) {
        return new AgentTool<>() {
            @Override
            public String name() {
                return name;
            }
            
            @Override
            public String description() {
                return description;
            }
            
            @Override
            public String label() {
                return label;
            }
            
            @Override
            public JsonNode parameters() {
                return parameters;
            }
            
            @Override
            public AgentToolResult<T> execute(
                String toolCallId,
                Map<String, Object> params,
                Consumer<AgentToolResult<T>> onUpdate
            ) {
                return executor.apply(toolCallId, params);
            }
        };
    }
}
