package com.pi.agent.event;

import com.pi.agent.model.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the agent event system.
 */
class AgentEventTest {

    @Test
    void testAgentStartEvent() {
        AgentEvent.AgentStart event = new AgentEvent.AgentStart();
        assertEquals("agent_start", event.type());
    }

    @Test
    void testAgentEndEvent() {
        AgentEvent.AgentEnd event = new AgentEvent.AgentEnd(java.util.List.of());
        assertEquals("agent_end", event.type());
        assertTrue(event.messages().isEmpty());
    }

    @Test
    void testTurnStartEvent() {
        AgentEvent.TurnStart event = new AgentEvent.TurnStart();
        assertEquals("turn_start", event.type());
    }

    @Test
    void testMessageStartEvent() {
        UserMessage message = UserMessage.of("Hello");
        AgentEvent.MessageStart event = new AgentEvent.MessageStart(message);
        
        assertEquals("message_start", event.type());
        assertEquals(message, event.message());
    }

    @Test
    void testToolExecutionStartEvent() {
        AgentEvent.ToolExecutionStart event = new AgentEvent.ToolExecutionStart(
            "call-123",
            "test-tool",
            java.util.Map.of("arg", "value")
        );
        
        assertEquals("tool_execution_start", event.type());
        assertEquals("call-123", event.toolCallId());
        assertEquals("test-tool", event.toolName());
    }

    @Test
    void testEventEmitter() {
        AgentEventEmitter emitter = new AgentEventEmitter();
        AtomicInteger count = new AtomicInteger(0);
        
        // Subscribe
        Runnable unsubscribe = emitter.subscribe(e -> count.incrementAndGet());
        
        // Emit events
        emitter.emit(new AgentEvent.AgentStart());
        emitter.emit(new AgentEvent.TurnStart());
        
        assertEquals(2, count.get());
        
        // Unsubscribe
        unsubscribe.run();
        
        emitter.emit(new AgentEvent.AgentStart());
        assertEquals(2, count.get()); // Should still be 2 after unsubscribe
    }

    @Test
    void testEventEmitterMultipleListeners() {
        AgentEventEmitter emitter = new AgentEventEmitter();
        AtomicInteger count1 = new AtomicInteger(0);
        AtomicInteger count2 = new AtomicInteger(0);
        
        emitter.subscribe(e -> count1.incrementAndGet());
        emitter.subscribe(e -> count2.incrementAndGet());
        
        emitter.emit(new AgentEvent.AgentStart());
        
        assertEquals(1, count1.get());
        assertEquals(1, count2.get());
        assertEquals(2, emitter.listenerCount());
    }

    @Test
    void testEventEmitterClear() {
        AgentEventEmitter emitter = new AgentEventEmitter();
        AtomicInteger count = new AtomicInteger(0);
        
        emitter.subscribe(e -> count.incrementAndGet());
        emitter.emit(new AgentEvent.AgentStart());
        assertEquals(1, count.get());
        
        emitter.clear();
        emitter.emit(new AgentEvent.AgentStart());
        assertEquals(1, count.get()); // Should still be 1 after clear
    }
}
