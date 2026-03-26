package com.pi.agent.model.hook;

import com.pi.agent.model.AgentContext;
import com.pi.agent.model.content.ToolCallContent;
import com.pi.agent.model.message.AssistantMessage;
import java.util.Map;

/**
 * Context passed to beforeToolCall hook.
 */
public record BeforeToolCallContext(
    AssistantMessage assistantMessage,
    ToolCallContent toolCall,
    Map<String, Object> args,
    AgentContext context
) {
}
