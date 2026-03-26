package com.pi.agent.service;

import com.pi.agent.event.*;
import com.pi.agent.model.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
public class Agent {
    @Getter
    private AgentState state;
    private final List<Consumer<AgentEvent>> listeners = new CopyOnWriteArrayList<>();
    private final List<AgentMessage> steeringQueue = new ArrayList<>();
    private final List<AgentMessage> followUpQueue = new ArrayList<>();
    
    public Agent() {
        this.state = AgentState.createDefault();
    }
    
    public Agent(AgentOptions options) {
        this.state = AgentState.createDefault();
        if (options.getSystemPrompt() != null) {
            state.setSystemPrompt(options.getSystemPrompt());
        }
        if (options.getModel() != null) {
            state.setModel(options.getModel());
        }
        if (options.getThinkingLevel() != null) {
            state.setThinkingLevel(options.getThinkingLevel());
        }
    }
    
    public Runnable subscribe(Consumer<AgentEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
    
    public void setSystemPrompt(String prompt) {
        state.setSystemPrompt(prompt);
    }
    
    public void setModel(ModelInfo model) {
        state.setModel(model);
    }
    
    public void setThinkingLevel(ThinkingLevel level) {
        state.setThinkingLevel(level);
    }
    
    public void appendMessage(AgentMessage message) {
        List<AgentMessage> msgs = new ArrayList<>(state.getMessages());
        msgs.add(message);
        state.setMessages(msgs);
    }
    
    public void clearMessages() {
        state.setMessages(new ArrayList<>());
    }
    
    public void steer(AgentMessage message) {
        steeringQueue.add(message);
    }
    
    public void followUp(AgentMessage message) {
        followUpQueue.add(message);
    }
    
    public void clearAllQueues() {
        steeringQueue.clear();
        followUpQueue.clear();
    }
    
    public boolean hasQueuedMessages() {
        return !steeringQueue.isEmpty() || !followUpQueue.isEmpty();
    }
    
    public Flux<AgentEvent> prompt(String text) {
        return prompt(UserMessage.of(text));
    }
    
    public Flux<AgentEvent> prompt(UserMessage message) {
        if (state.isStreaming()) {
            return Flux.error(new IllegalStateException("Agent is already processing"));
        }
        
        state.setStreaming(true);
        state.setError(null);
        appendMessage(message);
        
        Sinks.Many<AgentEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        
        // Emit events
        emit(sink, new AgentStartEvent());
        emit(sink, new TurnStartEvent());
        emit(sink, new MessageStartEvent(message));
        emit(sink, new MessageEndEvent(message));
        
        // TODO: Implement actual LLM call here
        // For now, just emit end events
        emit(sink, new AgentEndEvent(state.getMessages()));
        
        state.setStreaming(false);
        sink.tryEmitComplete();
        
        return sink.asFlux();
    }
    
    public void reset() {
        state.setMessages(new ArrayList<>());
        state.setStreaming(false);
        state.setError(null);
        steeringQueue.clear();
        followUpQueue.clear();
    }
    
    private void emit(Sinks.Many<AgentEvent> sink, AgentEvent event) {
        sink.tryEmitNext(event);
        for (Consumer<AgentEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.error("Error in event listener", e);
            }
        }
    }
}
