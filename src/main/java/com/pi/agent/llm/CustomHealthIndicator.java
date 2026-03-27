package com.pi.agent.llm;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Custom health indicator for LLM client resilience.
 * Aggregates health status from:
 * - Rate limiter
 * - Circuit breaker
 * - Retry budget
 * - Streaming timeout handler
 */
@Component("llmResilience")
public class LlmResilienceHealthIndicator implements HealthIndicator {

    private final EnhancedResilientClient client;

    public LlmResilienceHealthIndicator(EnhancedResilientClient client) {
        this.client = client;
    }

    
    @Override
    public Health health() {
        if (client == null) {
            return Health.unknown()
                .withDetail("reason", "Client not configured")
                .build();
        }

        try {
            EnhancedResilientClient.ClientStats stats = client.getStats();
            
            // Build health details
            Health.Builder builder = Health.status(determineStatus(stats))
                .withDetail("rateLimiter", buildRateLimiterDetails(stats.rateLimitStats()))
                .withDetail("circuitBreaker", buildCircuitBreakerDetails(stats.circuitBreakerStats()))
                .withDetail("retry", buildRetryDetails(stats))
                .withDetail("streaming", buildStreamingDetails());
            
            return builder.build();
            
        } catch (Exception e) {
            return Health.down(e)
                .withDetail("error", e.getMessage())
                .build();
        }
    }

    private Status determineStatus(EnhancedResilientClient.ClientStats stats) {
        // Circuit breaker takes precedence
        if (stats.circuitBreakerStats().state() == CircuitBreaker.State.OPEN) {
            return Status.DOWN;
        }
        
        // High utilization is degraded status
        if (stats.rateLimitStats().utilization() > 0.9) {
            return Status.OUT_OF_SERVICE;
        }
        
        // Low retry success rate is degraded status
        double retrySuccessRate = stats.retriedRequests() > 0 
            ? (double) stats.successfulRetries() / stats.retriedRequests() : 0.0;
        if (retrySuccessRate < 0.5) {
            return Status.OUT_OF_SERVICE;
        }
        
        return Status.UP;
    }

    private Map<String, Object> buildRateLimiterDetails(RateLimiter.RateLimitStats stats) {
        return Map.of(
            "utilization", String.format("%.2f", stats.utilization()),
            "activeRequests", stats.activeRequests(),
            "availableTokens", stats.availableTokens(),
            "rejectedRequests", stats.rejectedRequests(),
            "totalRequests", stats.totalRequests()
        );
    }

    private Map<String, Object> buildCircuitBreakerDetails(CircuitBreaker.CircuitBreakerStats stats) {
        return Map.of(
            "state", stats.state().name(),
            "failureRate", String.format("%.2f", stats.failureRate()),
            "currentFailureCount", stats.currentFailureCount(),
            "totalFailures", stats.totalFailures(),
            "totalSuccesses", stats.totalSuccesses()
        );
    }

    private Map<String, Object> buildRetryDetails(EnhancedResilientClient.ClientStats stats) {
        return Map.of(
            "totalRequests", stats.totalRequests(),
            "successfulRequests", stats.successfulRequests(),
            "failedRequests", stats.failedRequests(),
            "retriedRequests", stats.retriedRequests(),
            "retrySuccessRate", String.format("%.2f", 
                stats.retriedRequests() > 0 
                    ? (double) stats.successfulRetries() / stats.retriedRequests() : 0.0
                    : "N/A"),
            "budgetExhausted", stats.budgetExhaustedRequests()
        );
    }

    private Map<String, Object> buildStreamingDetails() {
        StreamingTimeoutHandler.TimeoutStats timeoutStats = client.getTimeoutHandler().getStats();
        return Map.of(
            "totalTimeouts", timeoutStats.totalTimeouts(),
            "connectionTimeouts", timeoutStats.connectionTimeouts(),
            "idleTimeouts", timeoutStats.idleTimeouts()
        );
    }
}
