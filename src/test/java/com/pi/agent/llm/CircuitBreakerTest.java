package com.pi.agent.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CircuitBreaker functionality.
 */
class CircuitBreakerTest {

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        CircuitBreaker.CircuitBreakerConfig config = CircuitBreaker.CircuitBreakerConfig.builder()
            .failureThreshold(3)
            .openDuration(Duration.ofMillis(500))
            .halfOpenMaxRequests(2)
            .halfOpenSuccessThreshold(2)
            .checkInterval(Duration.ofMillis(50))
            .build();
        
        circuitBreaker = new CircuitBreaker("test-circuit", config);
    }

    @Test
    @DisplayName("Should start in CLOSED state")
    void shouldStartInClosedState() {
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    @DisplayName("Should pass requests in CLOSED state")
    void shouldPassRequestsInClosedState() {
        Mono<String> success = Mono.just("success");
        
        StepVerifier.create(circuitBreaker.execute(success))
            .expectNext("success")
            .verifyComplete();
        
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    @DisplayName("Should transition to OPEN after threshold failures")
    void shouldTransitionToOpenAfterThresholdFailures() {
        RuntimeException error = new RuntimeException("test error");
        
        // Execute 3 failing requests to trip the circuit
        for (int i = 0; i < 3; i++) {
            circuitBreaker.execute(Mono.error(error))
                .onErrorReturn("ignored")
                .block();
        }
        
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }

    @Test
    @DisplayName("Should reject requests in OPEN state")
    void shouldRejectRequestsInOpenState() {
        circuitBreaker.forceOpen();
        
        Mono<String> request = Mono.just("should not execute");
        
        StepVerifier.create(circuitBreaker.execute(request))
            .expectError(CircuitBreakerOpenException.class)
            .verify();
    }

    @Test
    @DisplayName("Should include retry-after in OPEN exception")
    void shouldIncludeRetryAfterInOpenException() {
        circuitBreaker.forceOpen();
        
        StepVerifier.create(circuitBreaker.execute(Mono.just("test")))
            .expectErrorMatches(error -> 
                error instanceof CircuitBreakerOpenException &&
                ((CircuitBreakerOpenException) error).hasRetryAfter()
            )
            .verify();
    }

    @Test
    @DisplayName("Should transition to HALF_OPEN after timeout")
    void shouldTransitionToHalfOpenAfterTimeout() throws InterruptedException {
        circuitBreaker.forceOpen();
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        
        // Wait for open duration to pass
        Thread.sleep(600);
        
        assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());
    }

    @Test
    @DisplayName("Should recover to CLOSED after successes in HALF_OPEN")
    void shouldRecoverToClosedAfterSuccessesInHalfOpen() throws InterruptedException {
        circuitBreaker.forceOpen();
        Thread.sleep(600); // Wait for half-open
        
        // Execute successful requests
        for (int i = 0; i < 2; i++) {
            circuitBreaker.execute(Mono.just("success"))
                .onErrorReturn("ignored")
                .block();
        }
        
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    @DisplayName("Should transition back to OPEN on failure in HALF_OPEN")
    void shouldTransitionBackToOpenOnFailureInHalfOpen() throws InterruptedException {
        circuitBreaker.forceOpen();
        Thread.sleep(600); // Wait for half-open
        
        // Execute a failing request
        circuitBreaker.execute(Mono.error(new RuntimeException("error")))
            .onErrorReturn("ignored")
            .block();
        
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }

    @Test
    @DisplayName("Should track statistics correctly")
    void shouldTrackStatisticsCorrectly() {
        CircuitBreaker.CircuitBreakerStats initialStats = circuitBreaker.getStats();
        assertEquals(0, initialStats.totalRequests());
        
        // Execute successful request
        circuitBreaker.execute(Mono.just("success")).block();
        
        CircuitBreaker.CircuitBreakerStats afterSuccessStats = circuitBreaker.getStats();
        assertEquals(1, afterSuccessStats.totalRequests());
        assertEquals(1, afterSuccessStats.totalSuccesses());
        
        // Execute failing request
        circuitBreaker.execute(Mono.error(new RuntimeException("error")))
            .onErrorReturn("ignored")
            .block();
        
        CircuitBreaker.CircuitBreakerStats afterFailureStats = circuitBreaker.getStats();
        assertEquals(2, afterFailureStats.totalRequests());
        assertEquals(1, afterFailureStats.totalFailures());
    }

    @Test
    @DisplayName("Should support force close")
    void shouldSupportForceClose() {
        circuitBreaker.forceOpen();
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        
        circuitBreaker.forceClose();
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        
        // Should now accept requests
        StepVerifier.create(circuitBreaker.execute(Mono.just("success")))
            .expectNext("success")
            .verifyComplete();
    }

    @Test
    @DisplayName("Should calculate success and failure rates")
    void shouldCalculateSuccessAndFailureRates() {
        // Execute 3 successful and 1 failed request
        for (int i = 0; i < 3; i++) {
            circuitBreaker.execute(Mono.just("success")).block();
        }
        circuitBreaker.execute(Mono.error(new RuntimeException("error")))
            .onErrorReturn("ignored")
            .block();
        
        CircuitBreaker.CircuitBreakerStats stats = circuitBreaker.getStats();
        assertEquals(0.75, stats.successRate(), 0.01);
        assertEquals(0.25, stats.failureRate(), 0.01);
    }
}
