package com.pi.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker for LLM API calls.
 * Implements the circuit breaker pattern to prevent cascading failures.
 * 
 * States:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Circuit is tripped, requests fail fast
 * - HALF_OPEN: Testing if service recovered, limited requests pass through
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final CircuitBreakerConfig config;
    private final String name;
    
    // State machine
    private final AtomicReference<CircuitState> state;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong totalFailures = new AtomicLong(0);
    private final AtomicLong totalSuccesses = new AtomicLong(0);
    private final AtomicLong circuitOpens = new AtomicLong(0);

    public CircuitBreaker(String name, CircuitBreakerConfig config) {
        this.name = name;
        this.config = config;
        this.state = new AtomicReference<>(new CircuitState(State.CLOSED, Instant.now()));
        
        // Start state monitor
        startStateMonitor();
    }

    /**
     * Execute a request through the circuit breaker.
     * Returns a Mono that either executes the request or fails fast.
     */
    public <T> Mono<T> execute(Mono<T> request) {
        return Mono.defer(() -> {
            totalRequests.incrementAndGet();
            
            CircuitState currentState = state.get();
            
            switch (currentState.state()) {
                case CLOSED:
                    return executeInClosedState(request);
                    
                case OPEN:
                    return handleOpenState();
                    
                case HALF_OPEN:
                    return executeInHalfOpenState(request);
                    
                default:
                    return Mono.error(new CircuitBreakerException(
                        "Unknown circuit breaker state: " + currentState.state()
                    ));
            }
        });
    }

    /**
     * Execute request in CLOSED state (normal operation).
     */
    private <T> Mono<T> executeInClosedState(Mono<T> request) {
        return request
            .doOnSuccess(v -> recordSuccess())
            .doOnError(e -> recordFailure(e))
            .doOnTerminate(() -> checkThreshold());
    }

    /**
     * Handle OPEN state - fail fast.
     */
    private <T> Mono<T> handleOpenState() {
        CircuitState currentState = state.get();
        Duration timeSinceOpen = Duration.between(currentState.since(), Instant.now());
        
        // Check if we should transition to HALF_OPEN
        if (timeSinceOpen.compareTo(config.openDuration()) >= 0) {
            transitionToHalfOpen();
            // After transitioning to half-open, tell caller to retry
            return Mono.error(new CircuitBreakerOpenException(
                "Circuit breaker '" + name + "' transitioned to HALF_OPEN. Please retry.",
                100
            ));
        }
        
        Duration retryAfter = config.openDuration().minus(timeSinceOpen);
        return Mono.error(new CircuitBreakerOpenException(
            "Circuit breaker '" + name + "' is OPEN. Retry after " + retryAfter.toMillis() + "ms",
            retryAfter.toMillis()
        ));
    }

    /**
     * Execute request in HALF_OPEN state (testing recovery).
     */
    private <T> Mono<T> executeInHalfOpenState(Mono<T> request) {
        int currentSuccesses = successCount.get();
        
        if (currentSuccesses >= config.halfOpenMaxRequests()) {
            // Already have enough successful requests in half-open state
            return Mono.error(new CircuitBreakerOpenException(
                "Circuit breaker '" + name + "' is in HALF_OPEN state, waiting for confirmation",
                1000
            ));
        }
        
        return request
            .doOnSuccess(v -> {
                int successes = successCount.incrementAndGet();
                if (successes >= config.halfOpenSuccessThreshold()) {
                    transitionToClosed();
                }
            })
            .doOnError(e -> {
                recordFailure(e);
                transitionToOpen();
            });
    }

    /**
     * Record a successful request.
     */
    private void recordSuccess() {
        totalSuccesses.incrementAndGet();
        failureCount.set(0);
    }

    /**
     * Record a failed request.
     */
    private void recordFailure(Throwable error) {
        int failures = failureCount.incrementAndGet();
        totalFailures.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        
        log.warn("Circuit breaker '{}' recorded failure {}/{}: {}",
            name, failures, config.failureThreshold(), error.getMessage());
    }

    /**
     * Check if failure threshold is exceeded and trip the circuit.
     */
    private void checkThreshold() {
        CircuitState currentState = state.get();
        
        if (currentState.state() == State.CLOSED && 
            failureCount.get() >= config.failureThreshold()) {
            transitionToOpen();
        }
    }

    /**
     * Transition to OPEN state.
     */
    private void transitionToOpen() {
        CircuitState current = state.get();
        CircuitState openState = new CircuitState(State.OPEN, Instant.now());
        
        if (state.compareAndSet(current, openState)) {
            circuitOpens.incrementAndGet();
            failureCount.set(0);
            log.error("Circuit breaker '{}' transitioned to OPEN state after {} failures",
                name, config.failureThreshold());
        }
    }

    /**
     * Transition to HALF_OPEN state.
     */
    private void transitionToHalfOpen() {
        CircuitState current = state.get();
        CircuitState halfOpenState = new CircuitState(State.HALF_OPEN, Instant.now());
        
        if (state.compareAndSet(current, halfOpenState)) {
            successCount.set(0);
            log.info("Circuit breaker '{}' transitioned to HALF_OPEN state", name);
        }
    }

    /**
     * Transition to CLOSED state.
     */
    private void transitionToClosed() {
        CircuitState current = state.get();
        CircuitState closedState = new CircuitState(State.CLOSED, Instant.now());
        
        if (state.compareAndSet(current, closedState)) {
            failureCount.set(0);
            successCount.set(0);
            log.info("Circuit breaker '{}' transitioned to CLOSED state (recovered)", name);
        }
    }

    /**
     * Start background state monitor for timeout transitions.
     */
    private void startStateMonitor() {
        Flux.interval(config.checkInterval())
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(tick -> {
                CircuitState currentState = state.get();
                
                if (currentState.state() == State.OPEN) {
                    Duration timeSinceOpen = Duration.between(currentState.since(), Instant.now());
                    if (timeSinceOpen.compareTo(config.openDuration()) >= 0) {
                        transitionToHalfOpen();
                    }
                }
            });
    }

    /**
     * Get current circuit breaker state.
     */
    public State getState() {
        return state.get().state();
    }

    /**
     * Get circuit breaker statistics.
     */
    public CircuitBreakerStats getStats() {
        return new CircuitBreakerStats(
            name,
            state.get().state(),
            failureCount.get(),
            config.failureThreshold(),
            successCount.get(),
            totalRequests.get(),
            totalFailures.get(),
            totalSuccesses.get(),
            circuitOpens.get(),
            lastFailureTime.get() > 0 ? Instant.ofEpochMilli(lastFailureTime.get()) : null
        );
    }

    /**
     * Force circuit breaker to OPEN state (for testing/maintenance).
     */
    public void forceOpen() {
        transitionToOpen();
    }

    /**
     * Force circuit breaker to CLOSED state (for testing/maintenance).
     */
    public void forceClose() {
        CircuitState closedState = new CircuitState(State.CLOSED, Instant.now());
        state.set(closedState);
        failureCount.set(0);
        successCount.set(0);
    }

    /**
     * Circuit breaker state enum.
     */
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /**
     * Circuit state record.
     */
    private record CircuitState(State state, Instant since) {}

    /**
     * Circuit breaker configuration.
     */
    public record CircuitBreakerConfig(
        int failureThreshold,
        Duration openDuration,
        int halfOpenMaxRequests,
        int halfOpenSuccessThreshold,
        Duration checkInterval
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private int failureThreshold = 5;
            private Duration openDuration = Duration.ofSeconds(30);
            private int halfOpenMaxRequests = 3;
            private int halfOpenSuccessThreshold = 2;
            private Duration checkInterval = Duration.ofMillis(100);

            public Builder failureThreshold(int failureThreshold) {
                this.failureThreshold = failureThreshold;
                return this;
            }

            public Builder openDuration(Duration openDuration) {
                this.openDuration = openDuration;
                return this;
            }

            public Builder halfOpenMaxRequests(int halfOpenMaxRequests) {
                this.halfOpenMaxRequests = halfOpenMaxRequests;
                return this;
            }

            public Builder halfOpenSuccessThreshold(int halfOpenSuccessThreshold) {
                this.halfOpenSuccessThreshold = halfOpenSuccessThreshold;
                return this;
            }

            public Builder checkInterval(Duration checkInterval) {
                this.checkInterval = checkInterval;
                return this;
            }

            public CircuitBreakerConfig build() {
                return new CircuitBreakerConfig(
                    failureThreshold, openDuration, halfOpenMaxRequests,
                    halfOpenSuccessThreshold, checkInterval
                );
            }
        }
    }

    /**
     * Circuit breaker statistics.
     */
    public record CircuitBreakerStats(
        String name,
        State state,
        int currentFailureCount,
        int failureThreshold,
        int currentSuccessCount,
        long totalRequests,
        long totalFailures,
        long totalSuccesses,
        long circuitOpens,
        Instant lastFailureTime
    ) {
        public double failureRate() {
            return totalRequests > 0 ? (double) totalFailures / totalRequests : 0.0;
        }

        public double successRate() {
            return totalRequests > 0 ? (double) totalSuccesses / totalRequests : 0.0;
        }
    }
}
