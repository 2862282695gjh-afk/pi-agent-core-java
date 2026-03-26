package com.pi.agent.event;

import com.pi.agent.model.message.AgentMessage;
import com.pi.agent.model.message.AssistantMessage;
import com.pi.agent.model.message.ToolResultMessage;
import java.util.List;

/**
 * Base interface for all agent events.
 * Events provide fine-grained lifecycle information for messages, turns, and tool executions.
 */
public sealed interface AgentEvent permits 
    AgentEvent.AgentStart,
    AgentEvent.AgentEnd,
    AgentEvent.TurnStart,
    AgentEvent.TurnEnd,
    AgentEvent.MessageStart,
    AgentEvent.MessageUpdate,
    AgentEvent.MessageEnd,
    AgentEvent.ToolExecutionStart,
    AgentEvent.ToolExecutionUpdate,
    AgentEvent.ToolExecutionEnd {
    
    String type();
    
    // Agent lifecycle events
    
    record AgentStart() implements AgentEvent {
        @Override
        public String type() {
            return "agent_start";
        }
    }
    
    record AgentEnd(List<AgentMessage> messages) implements AgentEvent {
        @Override
        public String type() {
            return "agent_end";
        }
    }
    
    // Turn lifecycle events
    
    record TurnStart() implements AgentEvent {
        @Override
        public String type() {
            return "turn_start";
        }
    }
    
    record TurnEnd(
        AgentMessage message,
        List<ToolResultMessage> toolResults
    ) implements AgentEvent {
        @Override
        public String type() {
            return "turn_end";
        }
    }
    
    // Message lifecycle events
    
    record MessageStart(AgentMessage message) implements AgentEvent {
        @Override
        public String type() {
            return "message_start";
        }
    }
    
    record MessageUpdate(
        AgentMessage message,
        AssistantMessageEvent assistantMessageEvent
    ) implements AgentEvent {
        @Override
        public String type() {
            return "message_update";
        }
    }
    
    record MessageEnd(AgentMessage message) implements AgentEvent {
        @Override
        public String type() {
            return "message_end";
        }
    }
    
    // Tool execution lifecycle events
    
    record ToolExecutionStart(
        String toolCallId,
        String toolName,
        Object args
    ) implements AgentEvent {
        @Override
        public String type() {
            return "tool_execution_start";
        }
    }
    
    record ToolExecutionUpdate(
        String toolCallId,
        String toolName,
        Object args,
        Object partialResult
    ) implements AgentEvent {
        @Override
        public String type() {
            return "tool_execution_update";
        }
    }
    
    record ToolExecutionEnd(
        String toolCallId,
        String toolName,
        Object result,
        boolean isError
    ) implements AgentEvent {
        @Override
        public String type() {
            return "tool_execution_end";
        }
    }
}
