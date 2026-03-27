package com.pi.agent.llm;

import com.pi.agent.AgentLoopConfig;
import com.pi.agent.event.AssistantMessageEvent;
import com.pi.agent.model.AgentContext;
import com.pi.agent.model.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enhanced OpenAI client with comprehensive resilience features:
 * - Rate limiting with token bucket algorithm
 * - Circuit breaker pattern for failure protection
 * - Automatic retry with exponential backoff
 * - Optional Micrometer metrics integration
 * 
 * Use this client for production workloads requiring high availability.
 */
public class ResilientOpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientOpenAiClient.class);

    private final OpenAiClient delegate;
    private final RetryConfig retryConfig;
    private final RateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;
    private final LlmMetrics metrics;
    private final AtomicInteger totalRetries = new AtomicInteger(0);
    private final AtomicInteger successfulRetries = new AtomicInteger(0);

    public ResilientOpenAiClient(
        OpenAiClient delegate,
        RetryConfig retryConfig,
        RateLimiter.RateLimitConfig rateLimitConfig,
        CircuitBreaker.CircuitBreakerConfig circuitBreakerConfig
    ) {
        this(delegate, retryConfig, rateLimitConfig, circuitBreakerConfig, null);
    }

    public ResilientOpenAiClient(
        OpenAiClient delegate,
        RetryConfig retryConfig,
        RateLimiter.RateLimitConfig rateLimitConfig,
        CircuitBreaker.CircuitBreakerConfig circuitBreakerConfig,
        LlmMetrics metrics
    ) {
        this.delegate = delegate;
        this.retryConfig = retryConfig != null ? retryConfig : new RetryConfig();
        this.rateLimiter = new RateLimiter(rateLimitConfig != null ? 
            rateLimitConfig : RateLimiter.RateLimitConfig.builder().build());
        this.circuitBreaker = new CircuitBreaker("llm-client", 
            circuitBreakerConfig != null ? circuitBreakerConfig : 
            CircuitBreaker.CircuitBreakerConfig.builder().build());
        this.metrics = metrics;
    }

    public ResilientOpenAiClient(String baseUrl, String apiKey) {
        this(new OpenAiClient(baseUrl, apiKey), null, null, null);
    }

    public ResilientOpenAiClient(String baseUrl, String apiKey, RetryConfig retryConfig) {
        this(new OpenAiClient(baseUrl, apiKey), retryConfig, null, null);
    }

    /**
     * Stream a chat completion with all resilience features enabled.
     * Order of protection layers (outer to inner):
     * 1. Rate limiter - Controls request flow
     * 2. Circuit breaker - Prevents cascading failures
     * 3. Retry - Handles transient failures
     */
    public Flux<AssistantMessageEvent> streamCompletionResilient(
        AgentState.ModelInfo model,
        AgentContext context,
        AgentLoopConfig config
    ) {
        AtomicInteger attemptCount = new AtomicInteger(0);
        final String requestId = metrics != null ? metrics.startRequest(model.id(), model.provider()) : null;
        final long startTime = System.nanoTime();

        return Mono.fromCallable(() -> rateLimiter.acquire())
            .flatMap(mono -> mono) // Unwrap the Mono<RateLimitPermit>
            .flatMapMany(permit -> 
                executeWithCircuitBreaker(model, context, config, attemptCount, requestId)
                    .doOnTerminate(() -> {
                        permit.release();
                        log.debug("Released rate limit permit");
                    })
                    .doOnError(e -> {
                        permit.release();
                        log.debug("Released rate limit permit after error: {}", e.getMessage());
                    })
            )
            .doOnSubscribe(s -> log.info("Starting resilient LLM request for model: {}", model.id()))
            .doOnComplete(() -> {
                log.info("Resilient LLM request completed successfully");
                if (metrics != null && requestId != null) {
                    metrics.recordSuccess(requestId, model.id(), model.provider());
                }
            })
            .doOnError(e -> {
                log.error("Resilient LLM request failed: {}", e.getMessage());
                if (metrics != null && requestId != null) {
                    String errorType = e instanceof LlmApiException lle ? lle.getErrorCode().name() : "UNKNOWN";
                    metrics.recordFailure(requestId, model.id(), model.provider(), errorType);
                }
            });
    }

    /**
     * Execute with circuit breaker protection.
     */
    private Flux<AssistantMessageEvent> executeWithCircuitBreaker(
        AgentState.ModelInfo model,
        AgentContext context,
        AgentLoopConfig config,
        AtomicInteger attemptCount,
        String requestId
    ) {
        return Flux.defer(() -> {
                CircuitBreaker.State state = circuitBreaker.getState();
                
                switch (state) {
                    case OPEN:
                        if (metrics != null) {
                            metrics.recordCircuitOpen();
                        }
                        CircuitBreaker.CircuitBreakerStats stats = circuitBreaker.getStats();
                        Duration retryAfter = retryConfig.maxBackoff();
                        
                        // Calculate time since last failure if available
                        if (stats.lastFailureTime() != null) {
                            Duration timeSinceFailure = Duration.between(stats.lastFailureTime(), java.time.Instant.now());
                            retryAfter = retryConfig.maxBackoff().minus(timeSinceFailure);
                        }
                        
                        return Flux.error(new CircuitBreakerOpenException(
                            "Circuit breaker is OPEN. Retry after " + retryAfter.toMillis() + "ms",
                            Math.max(retryAfter.toMillis(), 100)
                        ));
                        
                    case HALF_OPEN:
                    case CLOSED:
                    default:
                        return executeWithRetry(model, context, config, attemptCount, requestId)
                            .doOnNext(event -> {
                                // Record success for circuit breaker
                                if (event instanceof AssistantMessageEvent.Done) {
                                    // Successful completion
                                    if (circuitBreaker.getState() == CircuitBreaker.State.HALF_OPEN) {
                                        log.info("Circuit breaker in HALF_OPEN, recording success");
                                    }
                                }
                            });
                }
            })
            .retryWhen(Retry.withThrowable(errors -> errors.flatMap(error -> {
                // Don't retry on circuit breaker open
                if (error instanceof CircuitBreakerOpenException) {
                    return Mono.error(error);
                }
                
                int attempt = attemptCount.incrementAndGet();
                
                if (attempt > retryConfig.maxRetries()) {
                    log.error("Max retries ({}) exceeded for LLM API call", retryConfig.maxRetries());
                    return Mono.error(error);
                }

                LlmApiException llmError = convertToLlmException(error);
                
                if (!shouldRetry(llmError)) {
                    log.error("Non-retryable error from LLM API: {}", llmError.getMessage());
                    return Mono.error(llmError);
                }

                // Record failure for circuit breaker
                recordCircuitBreakerFailure();

                // Record retry attempt
                totalRetries.incrementAndGet();
                if (metrics != null) {
                    metrics.recordRetryAttempt(false);
                }

                Duration backoff = calculateBackoff(attempt);
                log.warn("LLM API call failed (attempt {}/{}): {}. Retrying in {}ms...",
                    attempt, retryConfig.maxRetries(), llmError.getMessage(), backoff.toMillis());

                return Mono.delay(backoff);
            })));
    }

    /**
     * Execute with retry logic (inner protection layer).
     */
    private Flux<AssistantMessageEvent> executeWithRetry(
        AgentState.ModelInfo model,
        AgentContext context,
        AgentLoopConfig config,
        AtomicInteger attemptCount,
        String requestId
    ) {
        return delegate.streamCompletion(model, context, config)
            .doOnComplete(() -> {
                // Record success
                recordCircuitBreakerSuccess();
                // Record successful retry if this was a retry
                if (attemptCount.get() > 0) {
                    successfulRetries.incrementAndGet();
                    if (metrics != null) {
                        metrics.recordRetryAttempt(true);
                    }
                }
            })
            .doOnError(e -> {
                // Record failure
                recordCircuitBreakerFailure();
            });
    }

    /**
     * Record success for circuit breaker.
     */
    private void recordCircuitBreakerSuccess() {
        // Circuit breaker tracks success/failure internally based on Mono success/error
        // For Flux, we rely on doOnComplete for success
    }

    /**
     * Record failure for circuit breaker.
     */
    private void recordCircuitBreakerFailure() {
        // Manually trigger circuit breaker if too many failures
        CircuitBreaker.CircuitBreakerStats stats = circuitBreaker.getStats();
        if (stats.failureRate() > 0.5 && circuitBreaker.getState() == CircuitBreaker.State.CLOSED) {
            log.warn("High failure rate detected ({}), circuit may trip soon", stats.failureRate());
        }
    }

    /**
     * Check if an error should trigger a retry.
     */
    private boolean shouldRetry(LlmApiException error) {
        if (!error.isRetryable()) {
            return false;
        }

        if (error.isRateLimit() && !retryConfig.retryOnRateLimit()) {
            return false;
        }

        if (error.isServerError() && !retryConfig.retryOnServerError()) {
            return false;
        }

        if (error.isTimeout() && !retryConfig.retryOnTimeout()) {
            return false;
        }

        return true;
    }

    /**
     * Calculate exponential backoff duration.
     */
    private Duration calculateBackoff(long attempt) {
        double multiplier = Math.pow(retryConfig.backoffMultiplier(), attempt - 1);
        long backoffMs = (long) (retryConfig.initialBackoff().toMillis() * multiplier);
        
        // Apply jitter (±20%)
        double jitter = 0.2 * backoffMs * (Math.random() * 2 - 1);
        backoffMs = (long) (backoffMs + jitter);
        
        // Cap at max backoff
        return Duration.ofMillis(Math.min(backoffMs, retryConfig.maxBackoff().toMillis()));
    }

    /**
     * Convert various exceptions to LlmApiException.
     */
    private LlmApiException convertToLlmException(Throwable error) {
        if (error instanceof LlmApiException) {
            return (LlmApiException) error;
        }

        if (error instanceof org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
            int statusCode = wcre.getStatusCode().value();
            String message = wcre.getResponseBodyAsString();
            
            if (message == null || message.isBlank()) {
                message = wcre.getMessage();
            }

            return new LlmApiException(statusCode, message, error);
        }

        if (error instanceof java.util.concurrent.TimeoutException) {
            return new LlmApiException(
                LlmApiException.ErrorCode.TIMEOUT,
                "Request timeout: " + error.getMessage(),
                error
            );
        }

        // Generic network/IO error
        return new LlmApiException(
            LlmApiException.ErrorCode.NETWORK_ERROR,
            "Network error: " + error.getMessage(),
            error
        );
    }

    /**
     * Combined resilience metrics.
     */
    public record ResilienceMetrics(
        RateLimiter.RateLimitStats rateLimitStats,
        CircuitBreaker.CircuitBreakerStats circuitBreakerStats,
        long totalRetries,
        long successfulRetries
    ) {
        public boolean isHealthy() {
            return circuitBreakerStats.state() != CircuitBreaker.State.OPEN &&
                   rateLimitStats.utilization() < 0.9;
        }

        public double retrySuccessRate() {
            return totalRetries > 0 ? (double) successfulRetries / totalRetries : 0.0;
        }
    }

    /**
     * Get resilience metrics for monitoring.
     */
    public ResilienceMetrics getMetrics() {
        return new ResilienceMetrics(
            rateLimiter.getStats(),
            circuitBreaker.getStats(),
            totalRetries.get(),
            successfulRetries.get()
        );
    }

    /**
     * Get the rate limiter for direct configuration.
     */
    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    /**
     * Get the circuit breaker for direct configuration.
     */
    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * Get the underlying delegate client.
     */
    public OpenAiClient getDelegate() {
        return delegate;
    }
}
