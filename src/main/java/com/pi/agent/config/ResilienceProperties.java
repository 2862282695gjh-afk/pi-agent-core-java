package com.pi.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Spring Boot configuration properties for LLM client resilience features.
 * Maps to Spring Boot configuration files (application.yml)
 * and supports environment variable overrides.
 */
@ConfigurationProperties(prefix = "pi.agent.llm")
public class ResilienceProperties {

    
    // ===== Retry Configuration =====
    private RetryConfig retry = new RetryConfig();
    
    // ===== Rate Limit Configuration =====
    private RateLimitConfig rateLimit = new RateLimitConfig();
    
    // ===== Circuit Breaker Configuration =====
    private CircuitBreakerConfig circuitBreaker = new CircuitBreakerConfig();
    
    // ===== Global Timeout =====
    private Duration globalTimeout = Duration.ofMinutes(5);
    
    // ===== Getters and Setters =====
    
    public RetryConfig getRetry() {
        return retry;
    }
    
    public void setRetry(RetryConfig retry) {
        this.retry = retry;
    }
    
    public RateLimitConfig getRateLimit() {
        return rateLimit;
    }
    
    public void setRateLimit(RateLimitConfig rateLimit) {
        this.rateLimit = rateLimit;
    }
    
    public CircuitBreakerConfig getCircuitBreaker() {
        return circuitBreaker;
    }
    
    public void setCircuitBreaker(CircuitBreakerConfig circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }
    
    public Duration getGlobalTimeout() {
        return globalTimeout;
    }
    
    public void setGlobalTimeout(Duration globalTimeout) {
        this.globalTimeout = globalTimeout;
    }
    
    // ===== Nested Config Classes =====
    
    public static class RetryConfig {
        private int maxRetries = 3;
        private Duration initialBackoff = Duration.ofSeconds(1);
        private Duration maxBackoff = Duration.ofSeconds(30);
        private double backoffMultiplier = 2.0;
        private boolean retryOnRateLimit = true;
        private boolean retryOnServerError = true;
        private boolean retryOnTimeout = true;
        private int budgetMaxTokens = 10;
        private Duration budgetReplenishPeriod = Duration.ofSeconds(10);
        private int budgetTokensPerReplenish = 2;
        private String jitterStrategy = "fixed";
        
        // Getters and Setters
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        
        public Duration getInitialBackoff() { return initialBackoff; }
        public void setInitialBackoff(Duration initialBackoff) { this.initialBackoff = initialBackoff; }
        
        public Duration getMaxBackoff() { return maxBackoff; }
        public void setMaxBackoff(Duration maxBackoff) { this.maxBackoff = maxBackoff; }
        
        public double getBackoffMultiplier() { return backoffMultiplier; }
        public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
        
        public boolean isRetryOnRateLimit() { return retryOnRateLimit; }
        public void setRetryOnRateLimit(boolean retryOnRateLimit) { this.retryOnRateLimit = retryOnRateLimit; }
        
        public boolean isRetryOnServerError() { return retryOnServerError; }
        public void setRetryOnServerError(boolean retryOnServerError) { this.retryOnServerError = retryOnServerError; }
        
        public boolean isRetryOnTimeout() { return retryOnTimeout; }
        public void setRetryOnTimeout(boolean retryOnTimeout) { this.retryOnTimeout = retryOnTimeout; }
        
        public int getBudgetMaxTokens() { return budgetMaxTokens; }
        public void setBudgetMaxTokens(int budgetMaxTokens) { this.budgetMaxTokens = budgetMaxTokens; }
        
        public Duration getBudgetReplenishPeriod() { return budgetReplenishPeriod; }
        public void setBudgetReplenishPeriod(Duration budgetReplenishPeriod) { this.budgetReplenishPeriod = budgetReplenishPeriod; }
        
        public int getBudgetTokensPerReplenish() { return budgetTokensPerReplenish; }
        public void setBudgetTokensPerReplenish(int budgetTokensPerReplenish) { this.budgetTokensPerReplenish = budgetTokensPerReplenish; }
        
        public String getJitterStrategy() { return jitterStrategy; }
        public void setJitterStrategy(String jitterStrategy) { this.jitterStrategy = jitterStrategy; }
    }
    
    public static class RateLimitConfig {
        private int maxConcurrentRequests = 10;
        private int tokensPerSecond = 10;
        private Duration maxWaitTime = Duration.ofSeconds(5);
        private int tokenBucketRefillRate = 1;
        
        // Getters and Setters
        public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
        public void setMaxConcurrentRequests(int maxConcurrentRequests) { this.maxConcurrentRequests = maxConcurrentRequests; }
        
        public int getTokensPerSecond() { return tokensPerSecond; }
        public void setTokensPerSecond(int tokensPerSecond) { this.tokensPerSecond = tokensPerSecond; }
        
        public Duration getMaxWaitTime() { return maxWaitTime; }
        public void setMaxWaitTime(Duration maxWaitTime) { this.maxWaitTime = maxWaitTime; }
        
        public int getTokenBucketRefillRate() { return tokenBucketRefillRate; }
        public void setTokenBucketRefillRate(int tokenBucketRefillRate) { this.tokenBucketRefillRate = tokenBucketRefillRate; }
    }
    
    public static class CircuitBreakerConfig {
        private int failureThreshold = 5;
        private Duration openDuration = Duration.ofSeconds(30);
        private int halfOpenMaxRequests = 3;
        private int halfOpenSuccessThreshold = 2;
        private Duration checkInterval = Duration.ofMillis(100);
        
        // Getters and Setters
        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        
        public Duration getOpenDuration() { return openDuration; }
        public void setOpenDuration(Duration openDuration) { this.openDuration = openDuration; }
        
        public int getHalfOpenMaxRequests() { return halfOpenMaxRequests; }
        public void setHalfOpenMaxRequests(int halfOpenMaxRequests) { this.halfOpenMaxRequests = halfOpenMaxRequests; }
        
        public int getHalfOpenSuccessThreshold() { return halfOpenSuccessThreshold; }
        public void setHalfOpenSuccessThreshold(int halfOpenSuccessThreshold) { this.halfOpenSuccessThreshold = halfOpenSuccessThreshold; }
        
        public Duration getCheckInterval() { return checkInterval; }
        public void setCheckInterval(Duration checkInterval) { this.checkInterval = checkInterval; }
    }
    
    /**
     * Create RetryConfig from these properties.
     */
    public com.pi.agent.llm.RetryConfig toRetryConfig() {
        return com.pi.agent.llm.RetryConfig.builder()
            .maxRetries(retry.getMaxRetries())
            .initialBackoff(retry.getInitialBackoff())
            .maxBackoff(retry.getMaxBackoff())
            .backoffMultiplier(retry.getBackoffMultiplier())
            .retryOnRateLimit(retry.isRetryOnRateLimit())
            .retryOnServerError(retry.isRetryOnServerError())
            .retryOnTimeout(retry.isRetryOnTimeout())
            .build();
    }
    
    /**
     * Create RateLimiter.RateLimitConfig from these properties.
     */
    public com.pi.agent.llm.RateLimiter.RateLimitConfig toRateLimitConfig() {
        return com.pi.agent.llm.RateLimiter.RateLimitConfig.builder()
            .maxConcurrentRequests(rateLimit.getMaxConcurrentRequests())
            .tokensPerBucket(rateLimit.getTokensPerSecond() * 10) // Convert to bucket size
            .refillRate(rateLimit.getTokenBucketRefillRate())
            .maxWaitTime(rateLimit.getMaxWaitTime())
            .build();
    }
    
    /**
     * Create CircuitBreaker.CircuitBreakerConfig from these properties.
     */
    public com.pi.agent.llm.CircuitBreaker.CircuitBreakerConfig toCircuitBreakerConfig() {
        return com.pi.agent.llm.CircuitBreaker.CircuitBreakerConfig.builder()
            .failureThreshold(circuitBreaker.getFailureThreshold())
            .openDuration(circuitBreaker.getOpenDuration())
            .halfOpenMaxRequests(circuitBreaker.getHalfOpenMaxRequests())
            .halfOpenSuccessThreshold(circuitBreaker.getHalfOpenSuccessThreshold())
            .checkInterval(circuitBreaker.getCheckInterval())
            .build();
    }
    
    /**
     * Create RetryBudget from these properties.
     */
    public com.pi.agent.llm.RetryBudget toRetryBudget() {
        return com.pi.agent.llm.RetryBudget.builder()
            .maxTokens(retry.getBudgetMaxTokens())
            .replenishPeriod(retry.getBudgetReplenishPeriod())
            .tokensPerReplenish(retry.getBudgetTokensPerReplenish())
            .build();
    }
}
