package com.pi.agent.llm;

import com.pi.agent.event.AssistantMessageEvent;
import com.pi.agent.model.AgentState;
import com.pi.agent.model.message.AssistantMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LlmTracing distributed tracing support.
 */
class LlmTracingTest {

    private LlmTracing tracing;
    private List<LlmTracing.Span> startedSpans;
    private List<LlmTracing.Span> endedSpans;

    @BeforeEach
    void setUp() {
        startedSpans = new CopyOnWriteArrayList<>();
        endedSpans = new CopyOnWriteArrayList<>();
        
        LlmTracing.SpanProcessor processor = new LlmTracing.SpanProcessor() {
            @Override
            public void onStart(LlmTracing.Span span) {
                startedSpans.add(span);
            }

            @Override
            public void onEnd(LlmTracing.Span span) {
                endedSpans.add(span);
            }
        };

        tracing = LlmTracing.builder()
            .serviceName("test-service")
            .enabled(true)
            .spanProcessor(processor)
            .build();
    }

    @Test
    @DisplayName("Should start span with correct attributes")
    void testStartSpan() {
        AgentState.ModelInfo model = AgentState.ModelInfo.of("openai", "gpt-4");
        
        LlmTracing.Span span = tracing.startSpan("llm.chat.completion", model);
        
        assertNotNull(span.spanId());
        assertEquals("llm.chat.completion", span.operationName());
        assertEquals("test-service", span.serviceName());
        assertEquals("openai", span.provider());
        assertEquals("gpt-4", span.model());
        assertNotNull(span.startTime());
        assertNull(span.endTime());
        assertEquals(LlmTracing.Span.Status.UNSET, span.status());
    }

    @Test
    @DisplayName("Should start child span with parent reference")
    void testStartChildSpan() {
        AgentState.ModelInfo model = AgentState.ModelInfo.of("openai", "gpt-4");
        
        LlmTracing.Span parent = tracing.startSpan("llm.chat.completion", model);
        LlmTracing.Span child = tracing.startChildSpan(parent, "attempt.1");
        
        assertNotNull(child.spanId());
        assertEquals(parent.spanId(), child.parentSpanId());
        assertEquals("attempt.1", child.operationName());
        assertEquals(parent.provider(), child.provider());
        assertEquals(parent.model(), child.model());
    }

    @Test
    @DisplayName("Should add events and attributes to span")
    void testSpanEventsAndAttributes() {
        AgentState.ModelInfo model = AgentState.ModelInfo.of("openai", "gpt-4");
        
        LlmTracing.Span span = tracing.startSpan("test", model);
        span.addEvent("subscribed");
        span.addEvent("data.received");
        span.setAttribute("custom.key", "value");
        
        assertTrue(span.events().containsKey("subscribed"));
        assertTrue(span.events().containsKey("data.received"));
        assertEquals("value", span.attributes().get("custom.key"));
    }

    @Test
    @DisplayName("Should end span with status")
    void testEndSpan() {
        AgentState.ModelInfo model = AgentState.ModelInfo.of("openai", "gpt-4");
        
        LlmTracing.Span span = tracing.startSpan("test", model);
        span.end(LlmTracing.Span.Status.OK);
        
        assertNotNull(span.endTime());
        assertEquals(LlmTracing.Span.Status.OK, span.status());
        assertTrue(span.getDuration().toMillis() >= 0);
    }

    @Test
    @DisplayName("Should end span with error")
    void testEndSpanWithError() {
        AgentState.ModelInfo model = AgentState.ModelInfo.of("openai", "gpt-4");
        
        LlmTracing.Span span = tracing.startSpan("test", model);
        span.end(LlmTracing.Span.Status.ERROR, "Connection failed");
        
        assertEquals(LlmTracing.Span.Status.ERROR, span.status());
        assertEquals("Connection failed", span.errorMessage());
    }

    @Test
    @DisplayName("Should track active spans")
    void testActiveSpans() {
        assertEquals(0, tracing.getActiveSpanCount());
        
        AgentState.ModelInfo model = AgentState.ModelInfo.of("openai", "gpt-4");
        tracing.startSpan("test1", model);
        
        assertEquals(1, tracing.getActiveSpanCount());
        
        tracing.startSpan("test2", model);
        
        assertEquals(2, tracing.getActiveSpanCount());
    }

    @Test
    @DisplayName("Should call processor on span start")
    void testSpanProcessor() {
        AgentState.ModelInfo model = AgentState.ModelInfo.of("openai", "gpt-4");
        
        tracing.startSpan("test", model);
        assertEquals(1, startedSpans.size());
    }

    @Test
    @DisplayName("NOOP span should not throw on any operation")
    void testNoopSpan() {
        LlmTracing.Span noop = LlmTracing.Span.NOOP;
        
        // Should not throw
        noop.addEvent("test");
        noop.setAttribute("key", "value");
        noop.setStatus(LlmTracing.Span.Status.OK);
        noop.end(LlmTracing.Span.Status.OK);
        noop.end(LlmTracing.Span.Status.ERROR, "error");
        
        // Verify NOOP is still NOOP
        assertEquals("", noop.spanId());
    }

    @Test
    @DisplayName("Should trace stream with span")
    void testTraceStream() {
        AgentState.ModelInfo model = AgentState.ModelInfo.of("openai", "gpt-4");
        LlmTracing.Span span = tracing.startSpan("test", model);
        
        Flux<AssistantMessageEvent> source = Flux.just(
            new AssistantMessageEvent.TextDelta(0, "Hello"),
            new AssistantMessageEvent.Done(null)
        );
        
        Flux<AssistantMessageEvent> traced = tracing.traceStream(source, span, "chat");
        
        StepVerifier.create(traced)
            .expectNextCount(2)
            .verifyComplete();
        
        // Span should be ended after stream completes
        assertEquals(LlmTracing.Span.Status.OK, span.status());
    }

    @Test
    @DisplayName("Should trace stream error")
    void testTraceStreamError() {
        AgentState.ModelInfo model = AgentState.ModelInfo.of("openai", "gpt-4");
        LlmTracing.Span span = tracing.startSpan("test", model);
        
        Flux<AssistantMessageEvent> source = Flux.error(
            new RuntimeException("Test error")
        );
        
        Flux<AssistantMessageEvent> traced = tracing.traceStream(source, span, "chat");
        
        StepVerifier.create(traced)
            .expectError(RuntimeException.class)
            .verify();
        
        // Span should be ended with error
        assertEquals(LlmTracing.Span.Status.ERROR, span.status());
        assertEquals("Test error", span.errorMessage());
    }

    @Test
    @DisplayName("Disabled tracing should return NOOP span")
    void testDisabledTracing() {
        LlmTracing disabled = LlmTracing.builder()
            .enabled(false)
            .build();
        
        AgentState.ModelInfo model = AgentState.ModelInfo.of("openai", "gpt-4");
        LlmTracing.Span span = disabled.startSpan("test", model);
        
        assertEquals(LlmTracing.Span.NOOP, span);
    }

    @Test
    @DisplayName("Should track total spans")
    void testTotalSpans() {
        assertEquals(0, tracing.getTotalSpans());
        
        AgentState.ModelInfo model = AgentState.ModelInfo.of("openai", "gpt-4");
        tracing.startSpan("test1", model);
        tracing.startSpan("test2", model);
        
        assertEquals(2, tracing.getTotalSpans());
    }

    @Test
    @DisplayName("Span should calculate duration correctly")
    void testSpanDuration() throws InterruptedException {
        AgentState.ModelInfo model = AgentState.ModelInfo.of("openai", "gpt-4");
        LlmTracing.Span span = tracing.startSpan("test", model);
        
        Thread.sleep(50);
        
        Duration duration = span.getDuration();
        assertTrue(duration.toMillis() >= 50);
        
        span.end(LlmTracing.Span.Status.OK);
        
        Duration finalDuration = span.getDuration();
        assertTrue(finalDuration.toMillis() >= 50);
    }
}
