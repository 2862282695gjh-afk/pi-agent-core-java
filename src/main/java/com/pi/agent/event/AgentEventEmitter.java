package com.pi.agent.event;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Event emitter for agent events.
 * Supports subscription-based event distribution.
 */
public class AgentEventEmitter {
    
    private final Set<Consumer<AgentEvent>> listeners = ConcurrentHashMap.newKeySet();
    
    /**
     * Subscribe to all agent events.
     * @param listener Event listener
     * @return Unsubscribe function
     */
    public Runnable subscribe(Consumer<AgentEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
    
    /**
     * Emit an event to all listeners.
     * @param event Event to emit
     */
    public void emit(AgentEvent event) {
        listeners.forEach(listener -> {
            try {
                listener.accept(event);
            } catch (Exception e) {
                // Log and continue
                System.err.println("Error in event listener: " + e.getMessage());
            }
        });
    }
    
    /**
     * Emit an event asynchronously.
     * @param event Event to emit
     * @return Mono<Void> for reactive handling
     */
    public reactor.core.publisher.Mono<Void> emitAsync(AgentEvent event) {
        return reactor.core.publisher.Mono.fromRunnable(() -> emit(event));
    }
    
    /**
     * Clear all listeners.
     */
    public void clear() {
        listeners.clear();
    }
    
    /**
     * Get the number of active listeners.
     */
    public int listenerCount() {
        return listeners.size();
    }
}
