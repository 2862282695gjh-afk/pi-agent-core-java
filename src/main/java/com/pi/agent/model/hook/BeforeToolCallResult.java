package com.pi.agent.model.hook;

/**
 * Result returned from beforeToolCall hook.
 * 
 * Returning blocked=true prevents the tool from executing.
 * The loop emits an error tool result instead.
 */
public record BeforeToolCallResult(
    boolean blocked,
    String reason
) {
    
    public static BeforeToolCallResult allow() {
        return new BeforeToolCallResult(false, null);
    }
    
    public static BeforeToolCallResult doBlock(String reason) {
        return new BeforeToolCallResult(true, reason);
    }
    
    public static BeforeToolCallResult doBlock() {
        return doBlock("Tool execution was blocked");
    }
}
