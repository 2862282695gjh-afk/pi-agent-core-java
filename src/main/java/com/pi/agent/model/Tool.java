package com.pi.agent.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Base interface for tool definitions.
 */
public interface Tool {
    /**
     * Tool name (identifier).
     */
    String name();
    
    /**
     * Tool description.
     */
    String description();
    
    /**
     * JSON Schema for tool parameters.
     */
    JsonNode parameters();
}
