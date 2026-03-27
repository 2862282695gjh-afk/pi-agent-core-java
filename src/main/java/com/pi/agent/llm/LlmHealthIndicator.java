package com.pi.agent.llm;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Health indicator for LLM client resilience status.
 * Reports the health of the circuit breaker and rate limiter.
 */
@Component
public class LlmHealthIndicator implements HealthIndicator {

    private final ResilientOpenAiClient resilientClient;
    private volatile boolean enabled = true;

    public LlmHealthIndicator(ResilientOpenAiClient resilientClient) {
        this.resilientClient = resilientClient;
    }

    @Override
    public Health health() {
        if (!enabled || resilientClient == null) {
            return Health.unknown()
                .withDetail("reason", "LLM client not configured")
                .build();
        }

        try {
            ResilientOpenAiClient.ResilienceMetrics metrics = resilientClient.getMetrics();
            CircuitBreaker.CircuitBreakerStats cbStats = metrics.circuitBreakerStats();
            RateLimiter.RateLimitStats rlStats = metrics.rateLimitStats();

            // Determine health status based on circuit breaker state
            Status status = switch (cbStats.state()) {
                case CLOSED -> Status.UP;  // Normal operation
                case HALF_OPEN -> Status.UP;  // Recovering, but still functional
                case OPEN -> Status.DOWN;  // Circuit open, not accepting requests
            };

            Health.Builder builder = Health.status(status)
                .withDetail("circuitBreaker", Map.of(
                    "state", cbStats.state().name(),
                    "failureRate", cbStats.failureRate(),
                    "successRate", cbStats.successRate(),
                    "totalRequests", cbStats.totalRequests(),
                    "currentFailureCount", cbStats.currentFailureCount()
                ))
                .withDetail("rateLimiter", Map.of(
                    "utilization", rlStats.utilization(),
                    "availableTokens", rlStats.availableTokens(),
                    "activeRequests", rlStats.activeRequests(),
                    "rejectedRequests", rlStats.rejectedRequests(),
                    "totalRequests", rlStats.totalRequests()
                ))
                .withDetail("retry", Map.of(
                    "totalRetries", metrics.totalRetries(),
                    "successfulRetries", metrics.successfulRetries()
                ));

            return builder.build();
        } catch (Exception e) {
            return Health.down(e)
                .withDetail("error", e.getMessage())
                .build();
        }
    }

    /**
     * Enable or disable the health check.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
