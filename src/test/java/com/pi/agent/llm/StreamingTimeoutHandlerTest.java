package com.pi.agent.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StreamingTimeoutHandler.
 */
class StreamingTimeoutHandlerTest {

    private StreamingTimeoutHandler handler;

    @BeforeEach
    void setUp() {
        handler = StreamingTimeoutHandler.builder()
            .connectionTimeout(Duration.ofMillis(100))
            .idleTimeout(Duration.ofMillis(200))
            .totalTimeout(Duration.ofSeconds(5))
            .build();
    }

    @Test
    @DisplayName("Should pass through successful stream")
    void testSuccessfulStream() {
        Flux<String> source = Flux.just("event1", "event2", "event3");

        Flux<String> result = handler.withTimeouts(source);

        StepVerifier.create(result)
            .expectNext("event1")
            .expectNext("event2")
            .expectNext("event3")
            .verifyComplete();
    }

    @Test
    @DisplayName("Should track timeout statistics")
    void testTimeoutStats() {
        // Initially no timeouts
        StreamingTimeoutHandler.TimeoutStats stats = handler.getStats();
        assertEquals(0, stats.totalTimeouts());
        assertEquals(0, stats.connectionTimeouts());
        assertEquals(0, stats.idleTimeouts());
    }

    @Test
    @DisplayName("Should create default handler")
    void testDefaultHandler() {
        StreamingTimeoutHandler defaultHandler = new StreamingTimeoutHandler();
        assertNotNull(defaultHandler.getStats());
    }

    @Test
    @DisplayName("Should reset statistics")
    void testResetStats() {
        handler.resetStats();
        StreamingTimeoutHandler.TimeoutStats stats = handler.getStats();
        assertEquals(0, stats.totalTimeouts());
    }

    @Test
    @DisplayName("Should handle empty stream")
    void testEmptyStream() {
        Flux<String> source = Flux.empty();

        Flux<String> result = handler.withTimeouts(source);

        StepVerifier.create(result)
            .verifyComplete();
    }

    @Test
    @DisplayName("Should handle error in stream")
    void testErrorInStream() {
        Flux<String> source = Flux.error(new RuntimeException("Test error"));

        Flux<String> result = handler.withTimeouts(source);

        StepVerifier.create(result)
            .expectError(RuntimeException.class)
            .verify();
    }

    @Test
    @DisplayName("With recovery should call recovery function on timeout")
    void testWithRecovery() {
        // Create a source that will timeout
        Flux<String> source = Flux.just("event1")
            .delaySubscription(Duration.ofSeconds(10)); // Will timeout

        Flux<String> result = source.transform(
            handler.withRecovery(timeout -> Flux.just("recovered"))
        );

        StepVerifier.create(result)
            .expectNext("recovered")
            .verifyComplete();
    }

    @Test
    @DisplayName("Should use builder correctly")
    void testBuilder() {
        StreamingTimeoutHandler customHandler = StreamingTimeoutHandler.builder()
            .connectionTimeout(Duration.ofSeconds(5))
            .idleTimeout(Duration.ofSeconds(30))
            .totalTimeout(Duration.ofMinutes(10))
            .emitPartialOnTimeout(true)
            .build();

        assertNotNull(customHandler);
    }
}
