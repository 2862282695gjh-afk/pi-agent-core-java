package com.pi.agent.llm;

/**
 * Exception thrown when circuit breaker is OPEN and requests are rejected.
 */
public class CircuitBreakerOpenException extends CircuitBreakerException {

    private final long retryAfterMs;

    public CircuitBreakerOpenException(String message, long retryAfterMs) {
        super(message);
        this.retryAfterMs = retryAfterMs;
    }

    /**
     * Get the suggested retry-after time in milliseconds.
     */
    public long getRetryAfterMs() {
        return retryAfterMs;
    }

    /**
     * Check if a retry-after time is specified.
     */
    public boolean hasRetryAfter() {
        return retryAfterMs > 0;
    }
}
