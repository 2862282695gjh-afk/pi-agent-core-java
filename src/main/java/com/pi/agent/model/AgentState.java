package com.pi.agent.model;

import com.pi.agent.model.message.AgentMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent state containing all configuration and conversation data.
 */
public record AgentState(
    String systemPrompt,
    ModelInfo model,
    ThinkingLevel thinkingLevel,
    List<AgentTool<?>> tools,
    List<AgentMessage> messages,
    boolean isStreaming,
    AgentMessage streamMessage,
    Set<String> pendingToolCalls,
    String error
) {
    
    public AgentState() {
        this(
            "",
            ModelInfo.defaultModel(),
            ThinkingLevel.OFF,
            Collections.emptyList(),
            new ArrayList<>(),
            false,
            null,
            new HashSet<>(),
            null
        );
    }
    
    public AgentState withSystemPrompt(String systemPrompt) {
        return new AgentState(
            systemPrompt, model, thinkingLevel, tools, messages,
            isStreaming, streamMessage, pendingToolCalls, error
        );
    }
    
    public AgentState withModel(ModelInfo model) {
        return new AgentState(
            systemPrompt, model, thinkingLevel, tools, messages,
            isStreaming, streamMessage, pendingToolCalls, error
        );
    }
    
    public AgentState withThinkingLevel(ThinkingLevel thinkingLevel) {
        return new AgentState(
            systemPrompt, model, thinkingLevel, tools, messages,
            isStreaming, streamMessage, pendingToolCalls, error
        );
    }
    
    public AgentState withTools(List<AgentTool<?>> tools) {
        return new AgentState(
            systemPrompt, model, thinkingLevel, tools, messages,
            isStreaming, streamMessage, pendingToolCalls, error
        );
    }
    
    public AgentState withMessages(List<AgentMessage> messages) {
        return new AgentState(
            systemPrompt, model, thinkingLevel, tools, messages,
            isStreaming, streamMessage, pendingToolCalls, error
        );
    }
    
    public AgentState streaming(boolean isStreaming) {
        return new AgentState(
            systemPrompt, model, thinkingLevel, tools, messages,
            isStreaming, streamMessage, pendingToolCalls, error
        );
    }
    
    public AgentState withError(String error) {
        return new AgentState(
            systemPrompt, model, thinkingLevel, tools, messages,
            isStreaming, streamMessage, pendingToolCalls, error
        );
    }
    
    /**
     * Model information.
     */
    public record ModelInfo(
        String api,
        String provider,
        String id
    ) {
        public static ModelInfo defaultModel() {
            return new ModelInfo("chat", "google", "gemini-2.5-flash-lite-preview-06-17");
        }
        
        public static ModelInfo of(String provider, String id) {
            return new ModelInfo("chat", provider, id);
        }
    }
}
