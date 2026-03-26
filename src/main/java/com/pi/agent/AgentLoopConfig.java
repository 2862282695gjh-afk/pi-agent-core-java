package com.pi.agent;

import com.pi.agent.model.AgentState;
import com.pi.agent.model.ToolExecutionMode;
import com.pi.agent.model.hook.AfterToolCallContext;
import com.pi.agent.model.hook.AfterToolCallResult;
import com.pi.agent.model.hook.BeforeToolCallContext;
import com.pi.agent.model.hook.BeforeToolCallResult;
import com.pi.agent.model.message.AgentMessage;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Configuration for the agent loop.
 */
public record AgentLoopConfig(
    AgentState.ModelInfo model,
    Object reasoning,
    String sessionId,
    Consumer<Object> onPayload,
    String transport,
    Object thinkingBudgets,
    Long maxRetryDelayMs,
    ToolExecutionMode toolExecution,
    BiFunction<BeforeToolCallContext, ContextView, Mono<BeforeToolCallResult>> beforeToolCall,
    BiFunction<AfterToolCallContext, ContextView, Mono<AfterToolCallResult>> afterToolCall,
    Function<List<AgentMessage>, Mono<List<AgentMessage>>> convertToLlm,
    BiFunction<List<AgentMessage>, ContextView, Mono<List<AgentMessage>>> transformContext,
    Function<String, Mono<String>> getApiKey,
    Supplier<List<AgentMessage>> getSteeringMessages,
    Supplier<List<AgentMessage>> getFollowUpMessages
) {
}
