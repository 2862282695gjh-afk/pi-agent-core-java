package com.pi.agent.llm;

/**
 * Exception thrown when rate limit is exceeded.
 */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterMs;

    public RateLimitExceededException(String message) {
        super(message);
        this.retryAfterMs = -1;
    }

    public RateLimitExceededException(String message, long retryAfterMs) {
        super(message);
        this.retryAfterMs = retryAfterMs;
    }

    public RateLimitExceededException(String message, Throwable cause) {
        super(message, cause);
        this.retryAfterMs = -1;
    }

    /**
     * Get the suggested retry-after time in milliseconds.
     * Returns -1 if not specified.
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
