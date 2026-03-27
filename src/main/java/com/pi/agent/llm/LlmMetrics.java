package com.pi.agent.llm;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer metrics for LLM client operations.
 * Provides comprehensive monitoring for requests, retries, rate limiting, and circuit breaker.
 */
@Component
public class LlmMetrics {

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Timer.Sample> activeRequests = new ConcurrentHashMap<>();
    private final AtomicLong activeRequestsGauge = new AtomicLong(0);

    // Counters
    private final Counter totalRequests;
    private final Counter successfulRequests;
    private final Counter failedRequests;
    private final Counter rateLimitedRequests;
    private final Counter circuitOpenRequests;
    private final Counter retryAttempts;
    private final Counter successfulRetries;

    // Tags for metrics
    private static final String METRIC_PREFIX = "pi.agent.llm.";
    private static final List<Tag> COMPONENT_TAGS = List.of(Tag.of("component", "llm-client"));

    public LlmMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // Initialize counters
        this.totalRequests = Counter.builder(METRIC_PREFIX + "requests.total")
            .description("Total number of LLM requests")
            .tags(COMPONENT_TAGS)
            .register(meterRegistry);

        this.successfulRequests = Counter.builder(METRIC_PREFIX + "requests.successful")
            .description("Number of successful LLM requests")
            .tags(COMPONENT_TAGS)
            .register(meterRegistry);

        this.failedRequests = Counter.builder(METRIC_PREFIX + "requests.failed")
            .description("Number of failed LLM requests")
            .tags(COMPONENT_TAGS)
            .register(meterRegistry);

        this.rateLimitedRequests = Counter.builder(METRIC_PREFIX + "requests.rate_limited")
            .description("Number of rate-limited LLM requests")
            .tags(COMPONENT_TAGS)
            .register(meterRegistry);

        this.circuitOpenRequests = Counter.builder(METRIC_PREFIX + "requests.circuit_open")
            .description("Number of requests rejected due to open circuit breaker")
            .tags(COMPONENT_TAGS)
            .register(meterRegistry);

        this.retryAttempts = Counter.builder(METRIC_PREFIX + "retries.attempts")
            .description("Total number of retry attempts")
            .tags(COMPONENT_TAGS)
            .register(meterRegistry);

        this.successfulRetries = Counter.builder(METRIC_PREFIX + "retries.successful")
            .description("Number of successful retries")
            .tags(COMPONENT_TAGS)
            .register(meterRegistry);

        // Register gauge for active requests
        Gauge.builder(METRIC_PREFIX + "requests.active", activeRequestsGauge, AtomicLong::get)
            .description("Number of currently active LLM requests")
            .tags(COMPONENT_TAGS)
            .register(meterRegistry);
    }

    /**
     * Start timing a request.
     */
    public String startRequest(String model, String provider) {
        String requestId = java.util.UUID.randomUUID().toString();
        Timer.Sample sample = Timer.start(meterRegistry);
        activeRequests.put(requestId, sample);
        activeRequestsGauge.incrementAndGet();
        totalRequests.increment();
        return requestId;
    }

    /**
     * Record a successful request completion.
     */
    public void recordSuccess(String requestId, String model, String provider) {
        Timer.Sample sample = activeRequests.remove(requestId);
        if (sample != null) {
            activeRequestsGauge.decrementAndGet();
            List<Tag> tags = List.of(
                Tag.of("component", "llm-client"),
                Tag.of("model", model),
                Tag.of("provider", provider)
            );
            sample.stop(Timer.builder(METRIC_PREFIX + "request.duration")
                .description("Duration of LLM requests")
                .tags(tags)
                .register(meterRegistry));
        }
        successfulRequests.increment();
    }

    /**
     * Record a failed request.
     */
    public void recordFailure(String requestId, String model, String provider, String errorType) {
        Timer.Sample sample = activeRequests.remove(requestId);
        if (sample != null) {
            activeRequestsGauge.decrementAndGet();
            List<Tag> tags = List.of(
                Tag.of("component", "llm-client"),
                Tag.of("model", model),
                Tag.of("provider", provider)
            );
            sample.stop(Timer.builder(METRIC_PREFIX + "request.duration")
                .description("Duration of LLM requests")
                .tags(tags)
                .register(meterRegistry));
        }
        failedRequests.increment();
        
        // Record error type
        Counter.builder(METRIC_PREFIX + "errors")
            .description("LLM errors by type")
            .tags(List.of(Tag.of("component", "llm-client"), Tag.of("error_type", errorType)))
            .register(meterRegistry)
            .increment();
    }

    /**
     * Record a rate-limited request.
     */
    public void recordRateLimited() {
        rateLimitedRequests.increment();
    }

    /**
     * Record a request rejected by circuit breaker.
     */
    public void recordCircuitOpen() {
        circuitOpenRequests.increment();
    }

    /**
     * Record a retry attempt.
     */
    public void recordRetryAttempt(boolean successful) {
        retryAttempts.increment();
        if (successful) {
            successfulRetries.increment();
        }
    }

    /**
     * Record token usage.
     */
    public void recordTokenUsage(String model, long inputTokens, long outputTokens) {
        Counter.builder(METRIC_PREFIX + "tokens.input")
            .description("Input tokens consumed")
            .tags(List.of(Tag.of("component", "llm-client"), Tag.of("model", model)))
            .register(meterRegistry)
            .increment(inputTokens);

        Counter.builder(METRIC_PREFIX + "tokens.output")
            .description("Output tokens generated")
            .tags(List.of(Tag.of("component", "llm-client"), Tag.of("model", model)))
            .register(meterRegistry)
            .increment(outputTokens);
    }

    /**
     * Record stream event latency.
     */
    public void recordStreamLatency(Duration latency, String eventType) {
        Timer.builder(METRIC_PREFIX + "stream.latency")
            .description("Latency for stream events")
            .tags(List.of(Tag.of("component", "llm-client"), Tag.of("event_type", eventType)))
            .register(meterRegistry)
            .record(latency);
    }

    /**
     * Get summary statistics.
     */
    public MetricsSummary getSummary() {
        return new MetricsSummary(
            (long) totalRequests.count(),
            (long) successfulRequests.count(),
            (long) failedRequests.count(),
            (long) rateLimitedRequests.count(),
            (long) circuitOpenRequests.count(),
            (long) retryAttempts.count(),
            (long) successfulRetries.count(),
            activeRequestsGauge.get()
        );
    }

    /**
     * Summary of metrics.
     */
    public record MetricsSummary(
        long totalRequests,
        long successfulRequests,
        long failedRequests,
        long rateLimitedRequests,
        long circuitOpenRequests,
        long retryAttempts,
        long successfulRetries,
        long activeRequests
    ) {
        public double successRate() {
            return totalRequests > 0 ? (double) successfulRequests / totalRequests : 0.0;
        }

        public double failureRate() {
            return totalRequests > 0 ? (double) failedRequests / totalRequests : 0.0;
        }
    }
}
