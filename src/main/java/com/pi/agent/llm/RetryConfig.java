package com.pi.agent.llm;

import java.time.Duration;

/**
 * Configuration for retry behavior in LLM API calls.
 */
public record RetryConfig(
    int maxRetries,
    Duration initialBackoff,
    Duration maxBackoff,
    double backoffMultiplier,
    boolean retryOnRateLimit,
    boolean retryOnServerError,
    boolean retryOnTimeout
) {
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(30);
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;

    public RetryConfig() {
        this(DEFAULT_MAX_RETRIES, DEFAULT_INITIAL_BACKOFF, DEFAULT_MAX_BACKOFF, 
             DEFAULT_BACKOFF_MULTIPLIER, true, true, true);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private Duration initialBackoff = DEFAULT_INITIAL_BACKOFF;
        private Duration maxBackoff = DEFAULT_MAX_BACKOFF;
        private double backoffMultiplier = DEFAULT_BACKOFF_MULTIPLIER;
        private boolean retryOnRateLimit = true;
        private boolean retryOnServerError = true;
        private boolean retryOnTimeout = true;

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
            return this;
        }

        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        public Builder backoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
            return this;
        }

        public Builder retryOnRateLimit(boolean retryOnRateLimit) {
            this.retryOnRateLimit = retryOnRateLimit;
            return this;
        }

        public Builder retryOnServerError(boolean retryOnServerError) {
            this.retryOnServerError = retryOnServerError;
            return this;
        }

        public Builder retryOnTimeout(boolean retryOnTimeout) {
            this.retryOnTimeout = retryOnTimeout;
            return this;
        }

        public RetryConfig build() {
            return new RetryConfig(
                maxRetries, initialBackoff, maxBackoff, backoffMultiplier,
                retryOnRateLimit, retryOnServerError, retryOnTimeout
            );
        }
    }
}
