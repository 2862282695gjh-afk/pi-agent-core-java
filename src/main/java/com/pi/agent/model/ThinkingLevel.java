package com.pi.agent.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Thinking/reasoning level for models that support it.
 * Note: "xhigh" is only supported by OpenAI gpt-5.1-codex-max, gpt-5.2, gpt-5.2-codex, gpt-5.3, and gpt-5.3-codex models.
 */
public enum ThinkingLevel {
    OFF("off"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh");
    
    private final String value;
    
    ThinkingLevel(String value) {
        this.value = value;
    }
    
    @JsonValue
    public String getValue() {
        return value;
    }
    
    public static ThinkingLevel fromValue(String value) {
        for (ThinkingLevel level : values()) {
            if (level.value.equals(value)) {
                return level;
            }
        }
        return OFF;
    }
}
