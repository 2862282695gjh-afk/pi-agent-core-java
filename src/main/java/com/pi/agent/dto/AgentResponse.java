package com.pi.agent.dto;

import com.pi.agent.model.AgentState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentResponse {
    private boolean success;
    private String message;
    private AgentState state;
    private long timestamp;
    
    public static AgentResponse success(String msg) {
        return AgentResponse.builder()
            .success(true)
            .message(msg)
            .timestamp(System.currentTimeMillis())
            .build();
    }
    
    public static AgentResponse error(String msg) {
        return AgentResponse.builder()
            .success(false)
            .message(msg)
            .timestamp(System.currentTimeMillis())
            .build();
    }
}
