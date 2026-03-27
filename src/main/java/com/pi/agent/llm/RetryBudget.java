package com.pi.agent.llm;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks retry budget to prevent excessive retries across multiple requests.
 * Implements a token bucket algorithm for retry budget management.
 * 
 * Use cases:
 * - Limit total retries within a time window
 * - Prevent retry storms during outages
 * - Allow budget to replenish over time
 */
public class RetryBudget {

    private final int maxTokens;
    private final Duration replenishPeriod;
    private final int tokensPerReplenish;
    
    private final AtomicInteger availableTokens;
    private final AtomicReference<Instant> lastReplenishTime;
    private final AtomicLong totalTokensConsumed = new AtomicLong(0);
    private final AtomicLong totalRequestsRejected = new AtomicLong(0);

    /**
     * Create a retry budget with default settings.
     * Default: 10 tokens, replenish 2 tokens every 10 seconds
     */
    public RetryBudget() {
        this(10, Duration.ofSeconds(10), 2);
    }

    /**
     * Create a retry budget with custom settings.
     * 
     * @param maxTokens Maximum tokens in the bucket
     * @param replenishPeriod How often to replenish tokens
     * @param tokensPerReplenish How many tokens to add per replenishment
     */
    public RetryBudget(int maxTokens, Duration replenishPeriod, int tokensPerReplenish) {
        this.maxTokens = maxTokens;
        this.replenishPeriod = replenishPeriod;
        this.tokensPerReplenish = tokensPerReplenish;
        this.availableTokens = new AtomicInteger(maxTokens);
        this.lastReplenishTime = new AtomicReference<>(Instant.now());
    }

    /**
     * Try to consume a token from the budget.
     * Will attempt to replenish tokens before consuming.
     * 
     * @return true if a token was successfully consumed, false if budget exhausted
     */
    public boolean tryConsume() {
        replenishIfNeeded();
        
        while (true) {
            int current = availableTokens.get();
            if (current <= 0) {
                totalRequestsRejected.incrementAndGet();
                return false;
            }
            if (availableTokens.compareAndSet(current, current - 1)) {
                totalTokensConsumed.incrementAndGet();
                return true;
            }
        }
    }

    /**
     * Return a token to the budget (e.g., when retry succeeds).
     */
    public void returnToken() {
        while (true) {
            int current = availableTokens.get();
            if (current >= maxTokens) {
                return; // Already at max
            }
            if (availableTokens.compareAndSet(current, current + 1)) {
                return;
            }
        }
    }

    /**
     * Replenish tokens if enough time has passed.
     */
    private void replenishIfNeeded() {
        Instant now = Instant.now();
        Instant lastReplenish = lastReplenishTime.get();
        
        if (Duration.between(lastReplenish, now).compareTo(replenishPeriod) >= 0) {
            if (lastReplenishTime.compareAndSet(lastReplenish, now)) {
                while (true) {
                    int current = availableTokens.get();
                    int newTokens = Math.min(current + tokensPerReplenish, maxTokens);
                    if (availableTokens.compareAndSet(current, newTokens)) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * Get current available tokens.
     */
    public int getAvailableTokens() {
        replenishIfNeeded();
        return availableTokens.get();
    }

    /**
     * Get budget statistics.
     */
    public RetryBudgetStats getStats() {
        return new RetryBudgetStats(
            maxTokens,
            getAvailableTokens(),
            totalTokensConsumed.get(),
            totalRequestsRejected.get()
        );
    }

    /**
     * Reset the budget to full capacity.
     */
    public void reset() {
        availableTokens.set(maxTokens);
        lastReplenishTime.set(Instant.now());
    }

    /**
     * Statistics for the retry budget.
     */
    public record RetryBudgetStats(
        int maxTokens,
        int availableTokens,
        long totalTokensConsumed,
        long totalRequestsRejected
    ) {
        public double utilization() {
            return (double) (maxTokens - availableTokens) / maxTokens;
        }

        public double rejectionRate() {
            long total = totalTokensConsumed + totalRequestsRejected;
            return total > 0 ? (double) totalRequestsRejected / total : 0.0;
        }
    }

    /**
     * Builder for RetryBudget configuration.
     */
    public static class Builder {
        private int maxTokens = 10;
        private Duration replenishPeriod = Duration.ofSeconds(10);
        private int tokensPerReplenish = 2;

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder replenishPeriod(Duration replenishPeriod) {
            this.replenishPeriod = replenishPeriod;
            return this;
        }

        public Builder tokensPerReplenish(int tokensPerReplenish) {
            this.tokensPerReplenish = tokensPerReplenish;
            return this;
        }

        public RetryBudget build() {
            return new RetryBudget(maxTokens, replenishPeriod, tokensPerReplenish);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
