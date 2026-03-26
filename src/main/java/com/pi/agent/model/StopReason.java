package com.pi.agent.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Reason for assistant message completion.
 */
public enum StopReason {
    END_TURN("end_turn"),
    STOP_SEQUENCE("stop_sequence"),
    TOOL_USE("tool_use"),
    ERROR("error"),
    ABORTED("aborted"),
    MAX_TOKENS("max_tokens");
    
    private final String value;
    
    StopReason(String value) {
        this.value = value;
    }
    
    @JsonValue
    public String getValue() {
        return value;
    }
    
    public static StopReason fromValue(String value) {
        for (StopReason reason : values()) {
            if (reason.value.equals(value)) {
                return reason;
            }
        }
        return END_TURN;
    }
}
