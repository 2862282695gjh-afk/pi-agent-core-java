package com.pi.agent.service;

import com.pi.agent.model.ModelInfo;
import com.pi.agent.model.ThinkingLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentOptions {
    private String systemPrompt;
    private ModelInfo model;
    private ThinkingLevel thinkingLevel;
}
