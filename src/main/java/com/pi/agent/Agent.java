package com.pi.agent;

import com.pi.agent.event.AgentEvent;
import com.pi.agent.event.AgentEventEmitter;
import com.pi.agent.model.*;
import com.pi.agent.model.content.Content;
import com.pi.agent.model.content.ImageContent;
import com.pi.agent.model.content.TextContent;
import com.pi.agent.model.hook.AfterToolCallContext;
import com.pi.agent.model.hook.AfterToolCallResult;
import com.pi.agent.model.hook.BeforeToolCallContext;
import com.pi.agent.model.hook.BeforeToolCallResult;
import com.pi.agent.model.message.AgentMessage;
import com.pi.agent.model.message.AssistantMessage;
import com.pi.agent.model.message.ToolResultMessage;
import com.pi.agent.model.message.UserMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Agent class that manages conversation state and executes the agent loop.
 * 
 * This is the main entry point for interacting with the agent system.
 * Uses Project Reactor for reactive streaming.
 */
public class Agent {
    
    private AgentState state;
    private final AgentEventEmitter emitter = new AgentEventEmitter();
    private final List<AgentMessage> steeringQueue = new ArrayList<>();
    private final List<AgentMessage> followUpQueue = new ArrayList<>();
    
    // Configuration
    private final Function<List<AgentMessage>, Mono<List<AgentMessage>>> convertToLlm;
    private final BiFunction<List<AgentMessage>, reactor.util.context.ContextView, Mono<List<AgentMessage>>> transformContext;
    private final SteeringMode steeringMode;
    private final FollowUpMode followUpMode;
    private final StreamFn streamFn;
    private final String sessionId;
    private final Function<String, Mono<String>> getApiKey;
    private final Consumer<Object> onPayload;
    private final Object thinkingBudgets;
    private final String transport;
    private final Long maxRetryDelayMs;
    private final ToolExecutionMode toolExecution;
    private final BiFunction<BeforeToolCallContext, reactor.util.context.ContextView, Mono<BeforeToolCallResult>> beforeToolCall;
    private final BiFunction<AfterToolCallContext, reactor.util.context.ContextView, Mono<AfterToolCallResult>> afterToolCall;
    
    private volatile boolean running = false;
    
    public enum SteeringMode {
        ALL,
        ONE_AT_A_TIME
    }
    
    public enum FollowUpMode {
        ALL,
        ONE_AT_A_TIME
    }
    
    /**
     * Functional interface for streaming LLM responses.
     */
    @FunctionalInterface
    public interface StreamFn {
        Flux<AgentEvent> stream(AgentState.ModelInfo model, AgentContext context, AgentLoopConfig config);
    }
    
    private Agent(Builder builder) {
        this.state = builder.state != null ? builder.state : new AgentState();
        this.convertToLlm = builder.convertToLlm != null ? builder.convertToLlm : this::defaultConvertToLlm;
        this.transformContext = builder.transformContext;
        this.steeringMode = builder.steeringMode != null ? builder.steeringMode : SteeringMode.ONE_AT_A_TIME;
        this.followUpMode = builder.followUpMode != null ? builder.followUpMode : FollowUpMode.ONE_AT_A_TIME;
        this.streamFn = builder.streamFn;
        this.sessionId = builder.sessionId;
        this.getApiKey = builder.getApiKey;
        this.onPayload = builder.onPayload;
        this.thinkingBudgets = builder.thinkingBudgets;
        this.transport = builder.transport != null ? builder.transport : "sse";
        this.maxRetryDelayMs = builder.maxRetryDelayMs;
        this.toolExecution = builder.toolExecution != null ? builder.toolExecution : ToolExecutionMode.PARALLEL;
        this.beforeToolCall = builder.beforeToolCall;
        this.afterToolCall = builder.afterToolCall;
    }
    
    /**
     * Default conversion to LLM messages - filters to user/assistant/toolResult.
     */
    private Mono<List<AgentMessage>> defaultConvertToLlm(List<AgentMessage> messages) {
        return Mono.just(messages.stream()
            .filter(m -> "user".equals(m.role()) || "assistant".equals(m.role()) || "toolResult".equals(m.role()))
            .toList());
    }
    
    // === State Accessors ===
    
    public AgentState getState() {
        return state;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public boolean isStreaming() {
        return state.isStreaming();
    }
    
    // === Subscription ===
    
    /**
     * Subscribe to agent events.
     * @return Unsubscribe function
     */
    public Runnable subscribe(Consumer<AgentEvent> listener) {
        return emitter.subscribe(listener);
    }
    
    // === State Mutators ===
    
    public void setSystemPrompt(String systemPrompt) {
        this.state = state.withSystemPrompt(systemPrompt);
    }
    
    public void setModel(AgentState.ModelInfo model) {
        this.state = state.withModel(model);
    }
    
    public void setThinkingLevel(ThinkingLevel level) {
        this.state = state.withThinkingLevel(level);
    }
    
    public void setTools(List<AgentTool<?>> tools) {
        this.state = state.withTools(tools);
    }
    
    public void replaceMessages(List<AgentMessage> messages) {
        this.state = state.withMessages(new ArrayList<>(messages));
    }
    
    public void appendMessage(AgentMessage message) {
        List<AgentMessage> newMessages = new ArrayList<>(state.messages());
        newMessages.add(message);
        this.state = state.withMessages(newMessages);
    }
    
    public void clearMessages() {
        this.state = state.withMessages(new ArrayList<>());
    }
    
    // === Message Queuing ===
    
    /**
     * Queue a steering message to be delivered during execution.
     */
    public void steer(AgentMessage message) {
        steeringQueue.add(message);
    }
    
    /**
     * Queue a follow-up message to be processed after agent finishes.
     */
    public void followUp(AgentMessage message) {
        followUpQueue.add(message);
    }
    
    public void clearSteeringQueue() {
        steeringQueue.clear();
    }
    
    public void clearFollowUpQueue() {
        followUpQueue.clear();
    }
    
    public void clearAllQueues() {
        steeringQueue.clear();
        followUpQueue.clear();
    }
    
    public boolean hasQueuedMessages() {
        return !steeringQueue.isEmpty() || !followUpQueue.isEmpty();
    }
    
    // === Abort ===
    
    public void abort() {
        // TODO: Implement abort via Flux cancellation
    }
    
    public Mono<Void> waitForIdle() {
        return running ? Mono.never() : Mono.empty();
    }
    
    public void reset() {
        this.state = new AgentState();
        steeringQueue.clear();
        followUpQueue.clear();
    }
    
    // === Prompt ===
    
    /**
     * Send a text prompt.
     */
    public Flux<AgentEvent> prompt(String text) {
        return prompt(text, List.of());
    }
    
    /**
     * Send a text prompt with images.
     */
    public Flux<AgentEvent> prompt(String text, List<ImageContent> images) {
        List<Content> content = new ArrayList<>();
        content.add(new TextContent(text));
        content.addAll(images);
        return prompt(new UserMessage(content));
    }
    
    /**
     * Send a single message.
     */
    public Flux<AgentEvent> prompt(AgentMessage message) {
        return prompt(List.of(message));
    }
    
    /**
     * Send multiple messages.
     */
    public Flux<AgentEvent> prompt(List<AgentMessage> messages) {
        if (state.isStreaming()) {
            return Flux.error(new IllegalStateException(
                "Agent is already processing a prompt. Use steer() or followUp() to queue messages."
            ));
        }
        
        AgentState.ModelInfo model = state.model();
        if (model == null) {
            return Flux.error(new IllegalStateException("No model configured"));
        }
        
        this.state = state.streaming(true);
        this.running = true;
        
        return runLoop(messages)
            .doOnTerminate(() -> {
                this.state = state.streaming(false);
                this.running = false;
            });
    }
    
    /**
     * Continue from current context.
     */
    public Flux<AgentEvent> continue_() {
        if (state.isStreaming()) {
            return Flux.error(new IllegalStateException(
                "Agent is already processing. Wait for completion before continuing."
            ));
        }
        
        List<AgentMessage> messages = state.messages();
        if (messages.isEmpty()) {
            return Flux.error(new IllegalStateException("No messages to continue from"));
        }
        
        if ("assistant".equals(messages.get(messages.size() - 1).role())) {
            // Check for queued messages
            List<AgentMessage> steering = dequeueSteeringMessages();
            if (!steering.isEmpty()) {
                return runLoop(steering);
            }
            
            List<AgentMessage> followUp = dequeueFollowUpMessages();
            if (!followUp.isEmpty()) {
                return runLoop(followUp);
            }
            
            return Flux.error(new IllegalStateException("Cannot continue from message role: assistant"));
        }
        
        return runLoop(null);
    }
    
    // === Internal Loop ===
    
    private Flux<AgentEvent> runLoop(List<AgentMessage> newMessages) {
        List<AgentMessage> allNewMessages = new ArrayList<>();
        if (newMessages != null) {
            allNewMessages.addAll(newMessages);
        }
        
        AgentContext context = new AgentContext(
            state.systemPrompt(),
            new ArrayList<>(state.messages()),
            state.tools()
        );
        
        if (newMessages != null) {
            context = context.withMessages(
                new ArrayList<>(context.messages()) {{
                    addAll(newMessages);
                }}
            );
        }
        
        AgentLoopConfig config = new AgentLoopConfig(
            state.model(),
            state.thinkingLevel() != ThinkingLevel.OFF ? state.thinkingLevel() : null,
            sessionId,
            onPayload,
            transport,
            thinkingBudgets,
            maxRetryDelayMs,
            toolExecution,
            null, // maxTokens
            null, // temperature
            beforeToolCall,
            afterToolCall,
            convertToLlm,
            transformContext,
            getApiKey,
            this::dequeueSteeringMessages,
            this::dequeueFollowUpMessages
        );
        
        return Flux.create(sink -> {
            // Emit agent_start
            emitter.emit(new AgentEvent.AgentStart());
            sink.next(new AgentEvent.AgentStart());
            
            // Emit turn_start
            emitter.emit(new AgentEvent.TurnStart());
            sink.next(new AgentEvent.TurnStart());
            
            // Emit message events for new messages
            if (newMessages != null) {
                for (AgentMessage msg : newMessages) {
                    emitter.emit(new AgentEvent.MessageStart(msg));
                    sink.next(new AgentEvent.MessageStart(msg));
                    emitter.emit(new AgentEvent.MessageEnd(msg));
                    sink.next(new AgentEvent.MessageEnd(msg));
                    appendMessage(msg);
                }
            }
            
            // TODO: Implement actual agent loop with LLM streaming
            // For now, just complete
            emitter.emit(new AgentEvent.AgentEnd(allNewMessages));
            sink.next(new AgentEvent.AgentEnd(allNewMessages));
            sink.complete();
        });
    }
    
    private List<AgentMessage> dequeueSteeringMessages() {
        if (steeringMode == SteeringMode.ONE_AT_A_TIME && !steeringQueue.isEmpty()) {
            AgentMessage first = steeringQueue.remove(0);
            return List.of(first);
        }
        
        List<AgentMessage> result = new ArrayList<>(steeringQueue);
        steeringQueue.clear();
        return result;
    }
    
    private List<AgentMessage> dequeueFollowUpMessages() {
        if (followUpMode == FollowUpMode.ONE_AT_A_TIME && !followUpQueue.isEmpty()) {
            AgentMessage first = followUpQueue.remove(0);
            return List.of(first);
        }
        
        List<AgentMessage> result = new ArrayList<>(followUpQueue);
        followUpQueue.clear();
        return result;
    }
    
    // === Builder ===
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private AgentState state;
        private Function<List<AgentMessage>, Mono<List<AgentMessage>>> convertToLlm;
        private BiFunction<List<AgentMessage>, reactor.util.context.ContextView, Mono<List<AgentMessage>>> transformContext;
        private SteeringMode steeringMode;
        private FollowUpMode followUpMode;
        private StreamFn streamFn;
        private String sessionId;
        private Function<String, Mono<String>> getApiKey;
        private Consumer<Object> onPayload;
        private Object thinkingBudgets;
        private String transport;
        private Long maxRetryDelayMs;
        private ToolExecutionMode toolExecution;
        private BiFunction<BeforeToolCallContext, reactor.util.context.ContextView, Mono<BeforeToolCallResult>> beforeToolCall;
        private BiFunction<AfterToolCallContext, reactor.util.context.ContextView, Mono<AfterToolCallResult>> afterToolCall;
        
        public Builder state(AgentState state) {
            this.state = state;
            return this;
        }
        
        public Builder systemPrompt(String systemPrompt) {
            if (this.state == null) {
                this.state = new AgentState();
            }
            this.state = this.state.withSystemPrompt(systemPrompt);
            return this;
        }
        
        public Builder model(AgentState.ModelInfo model) {
            if (this.state == null) {
                this.state = new AgentState();
            }
            this.state = this.state.withModel(model);
            return this;
        }
        
        public Builder thinkingLevel(ThinkingLevel level) {
            if (this.state == null) {
                this.state = new AgentState();
            }
            this.state = this.state.withThinkingLevel(level);
            return this;
        }
        
        public Builder convertToLlm(Function<List<AgentMessage>, Mono<List<AgentMessage>>> convertToLlm) {
            this.convertToLlm = convertToLlm;
            return this;
        }
        
        public Builder transformContext(BiFunction<List<AgentMessage>, reactor.util.context.ContextView, Mono<List<AgentMessage>>> transformContext) {
            this.transformContext = transformContext;
            return this;
        }
        
        public Builder steeringMode(SteeringMode mode) {
            this.steeringMode = mode;
            return this;
        }
        
        public Builder followUpMode(FollowUpMode mode) {
            this.followUpMode = mode;
            return this;
        }
        
        public Builder streamFn(StreamFn streamFn) {
            this.streamFn = streamFn;
            return this;
        }
        
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }
        
        public Builder getApiKey(Function<String, Mono<String>> getApiKey) {
            this.getApiKey = getApiKey;
            return this;
        }
        
        public Builder onPayload(Consumer<Object> onPayload) {
            this.onPayload = onPayload;
            return this;
        }
        
        public Builder thinkingBudgets(Object thinkingBudgets) {
            this.thinkingBudgets = thinkingBudgets;
            return this;
        }
        
        public Builder transport(String transport) {
            this.transport = transport;
            return this;
        }
        
        public Builder maxRetryDelayMs(Long maxRetryDelayMs) {
            this.maxRetryDelayMs = maxRetryDelayMs;
            return this;
        }
        
        public Builder toolExecution(ToolExecutionMode mode) {
            this.toolExecution = mode;
            return this;
        }
        
        public Builder beforeToolCall(BiFunction<BeforeToolCallContext, reactor.util.context.ContextView, Mono<BeforeToolCallResult>> beforeToolCall) {
            this.beforeToolCall = beforeToolCall;
            return this;
        }
        
        public Builder afterToolCall(BiFunction<AfterToolCallContext, reactor.util.context.ContextView, Mono<AfterToolCallResult>> afterToolCall) {
            this.afterToolCall = afterToolCall;
            return this;
        }
        
        public Agent build() {
            return new Agent(this);
        }
    }
}
