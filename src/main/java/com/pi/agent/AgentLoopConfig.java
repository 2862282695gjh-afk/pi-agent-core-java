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
    Integer maxTokens,
    Double temperature,
    BiFunction<BeforeToolCallContext, ContextView, Mono<BeforeToolCallResult>> beforeToolCall,
    BiFunction<AfterToolCallContext, ContextView, Mono<AfterToolCallResult>> afterToolCall,
    Function<List<AgentMessage>, Mono<List<AgentMessage>>> convertToLlm,
    BiFunction<List<AgentMessage>, ContextView, Mono<List<AgentMessage>>> transformContext,
    Function<String, Mono<String>> getApiKey,
    Supplier<List<AgentMessage>> getSteeringMessages,
    Supplier<List<AgentMessage>> getFollowUpMessages
) {
    /**
     * Create a builder for AgentLoopConfig.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private AgentState.ModelInfo model;
        private Object reasoning;
        private String sessionId;
        private Consumer<Object> onPayload;
        private String transport;
        private Object thinkingBudgets;
        private Long maxRetryDelayMs;
        private ToolExecutionMode toolExecution = ToolExecutionMode.SEQUENTIAL;
        private Integer maxTokens;
        private Double temperature;
        private BiFunction<BeforeToolCallContext, ContextView, Mono<BeforeToolCallResult>> beforeToolCall;
        private BiFunction<AfterToolCallContext, ContextView, Mono<AfterToolCallResult>> afterToolCall;
        private Function<List<AgentMessage>, Mono<List<AgentMessage>>> convertToLlm;
        private BiFunction<List<AgentMessage>, ContextView, Mono<List<AgentMessage>>> transformContext;
        private Function<String, Mono<String>> getApiKey;
        private Supplier<List<AgentMessage>> getSteeringMessages;
        private Supplier<List<AgentMessage>> getFollowUpMessages;

        public Builder model(AgentState.ModelInfo model) {
            this.model = model;
            return this;
        }

        public Builder reasoning(Object reasoning) {
            this.reasoning = reasoning;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder onPayload(Consumer<Object> onPayload) {
            this.onPayload = onPayload;
            return this;
        }

        public Builder transport(String transport) {
            this.transport = transport;
            return this;
        }

        public Builder thinkingBudgets(Object thinkingBudgets) {
            this.thinkingBudgets = thinkingBudgets;
            return this;
        }

        public Builder maxRetryDelayMs(Long maxRetryDelayMs) {
            this.maxRetryDelayMs = maxRetryDelayMs;
            return this;
        }

        public Builder toolExecution(ToolExecutionMode toolExecution) {
            this.toolExecution = toolExecution;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder beforeToolCall(BiFunction<BeforeToolCallContext, ContextView, Mono<BeforeToolCallResult>> beforeToolCall) {
            this.beforeToolCall = beforeToolCall;
            return this;
        }

        public Builder afterToolCall(BiFunction<AfterToolCallContext, ContextView, Mono<AfterToolCallResult>> afterToolCall) {
            this.afterToolCall = afterToolCall;
            return this;
        }

        public Builder convertToLlm(Function<List<AgentMessage>, Mono<List<AgentMessage>>> convertToLlm) {
            this.convertToLlm = convertToLlm;
            return this;
        }

        public Builder transformContext(BiFunction<List<AgentMessage>, ContextView, Mono<List<AgentMessage>>> transformContext) {
            this.transformContext = transformContext;
            return this;
        }

        public Builder getApiKey(Function<String, Mono<String>> getApiKey) {
            this.getApiKey = getApiKey;
            return this;
        }

        public Builder getSteeringMessages(Supplier<List<AgentMessage>> getSteeringMessages) {
            this.getSteeringMessages = getSteeringMessages;
            return this;
        }

        public Builder getFollowUpMessages(Supplier<List<AgentMessage>> getFollowUpMessages) {
            this.getFollowUpMessages = getFollowUpMessages;
            return this;
        }

        public AgentLoopConfig build() {
            return new AgentLoopConfig(
                model, reasoning, sessionId, onPayload, transport, thinkingBudgets,
                maxRetryDelayMs, toolExecution, maxTokens, temperature,
                beforeToolCall, afterToolCall, convertToLlm, transformContext,
                getApiKey, getSteeringMessages, getFollowUpMessages
            );
        }
    }
}
