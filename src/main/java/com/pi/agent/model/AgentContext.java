package com.pi.agent.model;

import com.pi.agent.model.message.AgentMessage;
import java.util.List;

/**
 * Agent context for a single turn.
 */
public record AgentContext(
    String systemPrompt,
    List<AgentMessage> messages,
    List<AgentTool<?>> tools
) {
    
    public AgentContext withMessages(List<AgentMessage> messages) {
        return new AgentContext(systemPrompt, messages, tools);
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String systemPrompt = "";
        private List<AgentMessage> messages = List.of();
        private List<AgentTool<?>> tools = List.of();
        
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }
        
        public Builder messages(List<AgentMessage> messages) {
            this.messages = messages;
            return this;
        }
        
        public Builder tools(List<AgentTool<?>> tools) {
            this.tools = tools;
            return this;
        }
        
        public AgentContext build() {
            return new AgentContext(systemPrompt, messages, tools);
        }
    }
}
