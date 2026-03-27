package com.pi.agent.llm;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Enhanced retry configuration with per-endpoint settings and jitter strategies.
 */
public record EndpointRetryConfig(
    RetryConfig defaultConfig,
    Map<String, RetryConfig> endpointConfigs,
    RetryBudget retryBudget,
    JitterStrategy jitterStrategy
) {
    private static final RetryConfig DEFAULT_CONFIG = new RetryConfig();
    private static final JitterStrategy DEFAULT_JITTER = JitterStrategy.fixed(0.2);

    public EndpointRetryConfig() {
        this(DEFAULT_CONFIG, Collections.emptyMap(), new RetryBudget(), DEFAULT_JITTER);
    }

    public EndpointRetryConfig(
        RetryConfig defaultConfig,
        Map<String, RetryConfig> endpointConfigs,
        RetryBudget retryBudget,
        JitterStrategy jitterStrategy
    ) {
        this.defaultConfig = defaultConfig != null ? defaultConfig : DEFAULT_CONFIG;
        this.endpointConfigs = endpointConfigs != null ? 
            Collections.unmodifiableMap(new HashMap<>(endpointConfigs)) : Collections.emptyMap();
        this.retryBudget = retryBudget != null ? retryBudget : new RetryBudget();
        this.jitterStrategy = jitterStrategy != null ? jitterStrategy : DEFAULT_JITTER;
    }

    /**
     * Get retry config for a specific endpoint.
     * Falls back to default config if no endpoint-specific config exists.
     * 
     * @param endpoint The endpoint identifier (e.g., "chat.completions", "embeddings")
     * @return The retry config for the endpoint
     */
    public RetryConfig getConfigForEndpoint(String endpoint) {
        return endpointConfigs.getOrDefault(endpoint, defaultConfig);
    }

    /**
     * Check if retry budget allows a retry.
     */
    public boolean hasRetryBudget() {
        return retryBudget.tryConsume();
    }

    /**
     * Return a retry token to the budget (e.g., when retry succeeds).
     */
    public void returnRetryBudget() {
        retryBudget.returnToken();
    }

    /**
     * Calculate jittered backoff for an endpoint.
     */
    public Duration calculateBackoff(String endpoint, int attempt) {
        RetryConfig config = getConfigForEndpoint(endpoint);
        double multiplier = Math.pow(config.backoffMultiplier(), attempt - 1);
        long baseBackoffMs = (long) (config.initialBackoff().toMillis() * multiplier);
        
        // Apply jitter
        long jitteredMs = jitterStrategy.applyJitter(baseBackoffMs, attempt);
        
        // Cap at max backoff
        long cappedMs = Math.min(jitteredMs, config.maxBackoff().toMillis());
        
        return Duration.ofMillis(Math.max(0, cappedMs));
    }

    /**
     * Get retry budget statistics.
     */
    public RetryBudget.RetryBudgetStats getBudgetStats() {
        return retryBudget.getStats();
    }

    /**
     * Builder for EndpointRetryConfig.
     */
    public static class Builder {
        private RetryConfig defaultConfig = DEFAULT_CONFIG;
        private Map<String, RetryConfig> endpointConfigs = new HashMap<>();
        private RetryBudget retryBudget = new RetryBudget();
        private JitterStrategy jitterStrategy = DEFAULT_JITTER;

        public Builder defaultConfig(RetryConfig defaultConfig) {
            this.defaultConfig = defaultConfig;
            return this;
        }

        /**
         * Add endpoint-specific retry configuration.
         */
        public Builder endpointConfig(String endpoint, RetryConfig config) {
            this.endpointConfigs.put(endpoint, config);
            return this;
        }

        /**
         * Configure chat completions endpoint.
         */
        public Builder chatCompletions(RetryConfig config) {
            return endpointConfig("chat.completions", config);
        }

        /**
         * Configure embeddings endpoint.
         */
        public Builder embeddings(RetryConfig config) {
            return endpointConfig("embeddings", config);
        }

        /**
         * Configure models endpoint.
         */
        public Builder models(RetryConfig config) {
            return endpointConfig("models", config);
        }

        public Builder retryBudget(RetryBudget retryBudget) {
            this.retryBudget = retryBudget;
            return this;
        }

        public Builder retryBudget(int maxTokens, Duration replenishPeriod, int tokensPerReplenish) {
            this.retryBudget = new RetryBudget(maxTokens, replenishPeriod, tokensPerReplenish);
            return this;
        }

        public Builder jitterStrategy(JitterStrategy jitterStrategy) {
            this.jitterStrategy = jitterStrategy;
            return this;
        }

        /**
         * Use fixed jitter with the given factor.
         */
        public Builder fixedJitter(double factor) {
            this.jitterStrategy = JitterStrategy.fixed(factor);
            return this;
        }

        /**
         * Use equal jitter.
         */
        public Builder equalJitter() {
            this.jitterStrategy = JitterStrategy.equal();
            return this;
        }

        /**
         * Use full jitter.
         */
        public Builder fullJitter() {
            this.jitterStrategy = JitterStrategy.full();
            return this;
        }

        /**
         * Use decorrelated jitter.
         */
        public Builder decorrelatedJitter(Duration initialBackoff, Duration maxBackoff) {
            this.jitterStrategy = JitterStrategy.decorrelated(
                initialBackoff.toMillis(), maxBackoff.toMillis());
            return this;
        }

        public EndpointRetryConfig build() {
            return new EndpointRetryConfig(defaultConfig, endpointConfigs, retryBudget, jitterStrategy);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
