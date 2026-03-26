package com.pi.agent.model.hook;

import com.pi.agent.model.AgentContext;
import com.pi.agent.model.AgentToolResult;
import com.pi.agent.model.content.ToolCallContent;
import com.pi.agent.model.message.AssistantMessage;
import java.util.Map;

/**
 * Context passed to afterToolCall hook.
 */
public record AfterToolCallContext(
    AssistantMessage assistantMessage,
    ToolCallContent toolCall,
    Map<String, Object> args,
    AgentToolResult<?> result,
    boolean isError,
    AgentContext context
) {
}
