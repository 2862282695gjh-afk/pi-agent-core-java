package com.pi.agent.llm;

import java.time.Duration;
import java.time.Instant;

/**
 * A permit returned by RateLimiter when a request is allowed to proceed.
 * Must be released after the API call completes.
 */
public class RateLimitPermit implements AutoCloseable {

    private final RateLimiter rateLimiter;
    private final RateLimiter.Token token;
    private final Instant acquiredAt;
    private volatile boolean released = false;

    public RateLimitPermit(RateLimiter rateLimiter, RateLimiter.Token token) {
        this.rateLimiter = rateLimiter;
        this.token = token;
        this.acquiredAt = token.acquiredAt();
    }

    /**
     * Get the timestamp when this permit was acquired.
     */
    public Instant acquiredAt() {
        return acquiredAt;
    }

    /**
     * Get the time elapsed since acquiring this permit.
     */
    public Duration elapsedTime() {
        return Duration.between(acquiredAt, Instant.now());
    }

    /**
     * Release this permit back to the rate limiter.
     * Safe to call multiple times - subsequent calls are no-ops.
     */
    @Override
    public void close() {
        release();
    }

    /**
     * Release this permit back to the rate limiter.
     */
    public void release() {
        if (!released) {
            released = true;
            rateLimiter.release();
        }
    }

    /**
     * Check if this permit has been released.
     */
    public boolean isReleased() {
        return released;
    }
}
