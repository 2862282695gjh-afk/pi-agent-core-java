package com.pi.agent.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Configuration for how tool calls from a single assistant message are executed.
 * 
 * - SEQUENTIAL: each tool call is prepared, executed, and finalized before the next one starts.
 * - PARALLEL: tool calls are prepared sequentially, then allowed tools execute concurrently.
 *   Final tool results are still emitted in assistant source order.
 */
public enum ToolExecutionMode {
    SEQUENTIAL("sequential"),
    PARALLEL("parallel");
    
    private final String value;
    
    ToolExecutionMode(String value) {
        this.value = value;
    }
    
    @JsonValue
    public String getValue() {
        return value;
    }
    
    public static ToolExecutionMode fromValue(String value) {
        if ("sequential".equals(value)) {
            return SEQUENTIAL;
        }
        return PARALLEL;
    }
}
