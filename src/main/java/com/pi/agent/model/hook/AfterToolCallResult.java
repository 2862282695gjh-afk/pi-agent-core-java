package com.pi.agent.model.hook;

import com.pi.agent.model.content.Content;
import java.util.List;

/**
 * Partial override returned from afterToolCall hook.
 * 
 * Merge semantics:
 * - content: if provided, replaces the tool result content array
 * - details: if provided, replaces the tool result details value
 * - isError: if provided, replaces the error flag
 * 
 * Omitted fields keep the original values.
 */
public record AfterToolCallResult(
    List<Content> content,
    Object details,
    Boolean isError
) {
    
    public static AfterToolCallResult empty() {
        return new AfterToolCallResult(null, null, null);
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private List<Content> content;
        private Object details;
        private Boolean isError;
        
        public Builder content(List<Content> content) {
            this.content = content;
            return this;
        }
        
        public Builder details(Object details) {
            this.details = details;
            return this;
        }
        
        public Builder isError(boolean isError) {
            this.isError = isError;
            return this;
        }
        
        public AfterToolCallResult build() {
            return new AfterToolCallResult(content, details, isError);
        }
    }
}
