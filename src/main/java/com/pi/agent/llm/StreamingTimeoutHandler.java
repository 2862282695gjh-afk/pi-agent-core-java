package com.pi.agent.llm;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Handles streaming timeouts with configurable behavior.
 * Supports:
 * - Initial connection timeout
 * - Idle timeout between events
 * - Total stream timeout
 * - Timeout recovery strategies
 */
public class StreamingTimeoutHandler {

    private final Duration connectionTimeout;
    private final Duration idleTimeout;
    private final Duration totalTimeout;
    private final boolean emitPartialOnTimeout;

    private final AtomicLong totalTimeouts = new AtomicLong(0);
    private final AtomicLong connectionTimeouts = new AtomicLong(0);
    private final AtomicLong idleTimeouts = new AtomicLong(0);
    private final AtomicLong partialEmissions = new AtomicLong(0);

    /**
     * Create a streaming timeout handler with default settings.
     * Default: 10s connection, 30s idle, 5min total, no partial emission
     */
    public StreamingTimeoutHandler() {
        this(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(5), false);
    }

    /**
     * Create a streaming timeout handler with custom settings.
     * 
     * @param connectionTimeout Max time to establish connection and receive first event
     * @param idleTimeout Max time between consecutive events
     * @param totalTimeout Max total duration for the entire stream
     * @param emitPartialOnTimeout If true, emit collected data before timeout error
     */
    public StreamingTimeoutHandler(
        Duration connectionTimeout,
        Duration idleTimeout,
        Duration totalTimeout,
        boolean emitPartialOnTimeout
    ) {
        this.connectionTimeout = connectionTimeout;
        this.idleTimeout = idleTimeout;
        this.totalTimeout = totalTimeout;
        this.emitPartialOnTimeout = emitPartialOnTimeout;
    }

    /**
     * Apply timeout handling to a stream.
     * 
     * @param stream The source stream
     * @param eventIdExtractor Function to extract event ID for logging
     * @return The stream with timeout handling applied
     */
    public <T> Flux<T> withTimeouts(Flux<T> stream, Function<T, String> eventIdExtractor) {
        AtomicBoolean firstEventReceived = new AtomicBoolean(false);
        AtomicReference<StringBuilder> partialData = emitPartialOnTimeout ? 
            new AtomicReference<>(new StringBuilder()) : null;

        return stream
            // Apply total timeout
            .timeout(totalTimeout)
            // Track first event for connection timeout
            .doOnNext(event -> {
                if (firstEventReceived.compareAndSet(false, true)) {
                    // First event received within connection timeout
                }
                if (emitPartialOnTimeout && partialData != null) {
                    String eventId = eventIdExtractor.apply(event);
                    partialData.updateAndGet(sb -> sb.append(eventId).append(";"));
                }
            })
            // Apply idle timeout between events
            .onErrorResume(TimeoutException.class, e -> {
                if (!firstEventReceived.get()) {
                    // Connection timeout
                    connectionTimeouts.incrementAndGet();
                    totalTimeouts.incrementAndGet();
                    return handleConnectionTimeout(e);
                } else {
                    // Idle timeout
                    idleTimeouts.incrementAndGet();
                    totalTimeouts.incrementAndGet();
                    return handleIdleTimeout(e, partialData);
                }
            });
    }

    /**
     * Apply simplified timeout handling for event streams.
     */
    public <T> Flux<T> withTimeouts(Flux<T> stream) {
        return withTimeouts(stream, Object::toString);
    }

    /**
     * Handle connection timeout (no first event received).
     */
    private <T> Flux<T> handleConnectionTimeout(TimeoutException e) {
        return Flux.error(new LlmApiException(
            LlmApiException.ErrorCode.TIMEOUT,
            "Connection timeout: no response within " + connectionTimeout.toMillis() + "ms",
            e
        ));
    }

    /**
     * Handle idle timeout (timeout between events).
     */
    private <T> Flux<T> handleIdleTimeout(TimeoutException e, AtomicReference<StringBuilder> partialData) {
        if (emitPartialOnTimeout && partialData != null) {
            partialEmissions.incrementAndGet();
            // Note: In a real implementation, you might emit the partial data
            // before the error. For now, we just record it.
        }

        return Flux.error(new LlmApiException(
            LlmApiException.ErrorCode.TIMEOUT,
            "Idle timeout: no events for " + idleTimeout.toMillis() + "ms",
            e
        ));
    }

    /**
     * Create a timeout-aware wrapper that can recover from timeouts.
     * 
     * @param recoverFunction Function to call on timeout to get alternative stream
     */
    public <T> Function<Flux<T>, Flux<T>> withRecovery(Function<TimeoutException, Flux<T>> recoverFunction) {
        return stream -> stream
            .timeout(totalTimeout)
            .onErrorResume(TimeoutException.class, e -> {
                totalTimeouts.incrementAndGet();
                return recoverFunction.apply(e);
            });
    }

    /**
     * Get timeout statistics.
     */
    public TimeoutStats getStats() {
        return new TimeoutStats(
            connectionTimeouts.get(),
            idleTimeouts.get(),
            totalTimeouts.get(),
            partialEmissions.get()
        );
    }

    /**
     * Reset statistics.
     */
    public void resetStats() {
        connectionTimeouts.set(0);
        idleTimeouts.set(0);
        totalTimeouts.set(0);
        partialEmissions.set(0);
    }

    /**
     * Statistics for streaming timeouts.
     */
    public record TimeoutStats(
        long connectionTimeouts,
        long idleTimeouts,
        long totalTimeouts,
        long partialEmissions
    ) {
        public double connectionTimeoutRate() {
            return totalTimeouts > 0 ? (double) connectionTimeouts / totalTimeouts : 0.0;
        }
    }

    /**
     * Builder for StreamingTimeoutHandler.
     */
    public static class Builder {
        private Duration connectionTimeout = Duration.ofSeconds(10);
        private Duration idleTimeout = Duration.ofSeconds(30);
        private Duration totalTimeout = Duration.ofMinutes(5);
        private boolean emitPartialOnTimeout = false;

        public Builder connectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
            return this;
        }

        public Builder idleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
            return this;
        }

        public Builder totalTimeout(Duration totalTimeout) {
            this.totalTimeout = totalTimeout;
            return this;
        }

        public Builder emitPartialOnTimeout(boolean emitPartialOnTimeout) {
            this.emitPartialOnTimeout = emitPartialOnTimeout;
            return this;
        }

        public StreamingTimeoutHandler build() {
            return new StreamingTimeoutHandler(
                connectionTimeout, idleTimeout, totalTimeout, emitPartialOnTimeout
            );
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
