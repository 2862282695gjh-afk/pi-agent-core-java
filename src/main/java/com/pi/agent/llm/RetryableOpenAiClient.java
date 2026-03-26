package com.pi.agent.llm;

import com.pi.agent.AgentLoopConfig;
import com.pi.agent.event.AssistantMessageEvent;
import com.pi.agent.model.AgentContext;
import com.pi.agent.model.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAI client with automatic retry logic for transient failures.
 * Wraps OpenAiClient and adds exponential backoff retry behavior.
 */
public class RetryableOpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(RetryableOpenAiClient.class);

    private final OpenAiClient delegate;
    private final RetryConfig retryConfig;

    public RetryableOpenAiClient(OpenAiClient delegate, RetryConfig retryConfig) {
        this.delegate = delegate;
        this.retryConfig = retryConfig != null ? retryConfig : new RetryConfig();
    }

    public RetryableOpenAiClient(String baseUrl, String apiKey, RetryConfig retryConfig) {
        this.delegate = new OpenAiClient(baseUrl, apiKey);
        this.retryConfig = retryConfig != null ? retryConfig : new RetryConfig();
    }

    public RetryableOpenAiClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, null);
    }

    /**
     * Stream a chat completion with automatic retry on transient failures.
     */
    public Flux<AssistantMessageEvent> streamCompletionWithRetry(
        AgentState.ModelInfo model,
        AgentContext context,
        AgentLoopConfig config
    ) {
        AtomicInteger attemptCount = new AtomicInteger(0);

        return Flux.defer(() -> {
                int attempt = attemptCount.incrementAndGet();
                log.debug("LLM API call attempt {}/{}", attempt, retryConfig.maxRetries());
                return delegate.streamCompletion(model, context, config);
            })
            .retryWhen(Retry.withThrowable(errors -> errors.flatMap(error -> {
                int attempt = attemptCount.get();
                
                if (attempt > retryConfig.maxRetries()) {
                    log.error("Max retries ({}) exceeded for LLM API call", retryConfig.maxRetries());
                    return Mono.error(error);
                }

                LlmApiException llmError = convertToLlmException(error);
                
                if (!shouldRetry(llmError)) {
                    log.error("Non-retryable error from LLM API: {}", llmError.getMessage());
                    return Mono.error(llmError);
                }

                Duration backoff = calculateBackoff(attempt);
                log.warn("LLM API call failed (attempt {}/{}): {}. Retrying in {}ms...",
                    attempt, retryConfig.maxRetries(), llmError.getMessage(), backoff.toMillis());

                return Mono.delay(backoff);
            })))
            .doOnSubscribe(s -> log.debug("Starting LLM streaming request for model: {}", model.id()))
            .doOnComplete(() -> log.debug("LLM streaming completed successfully"))
            .doOnError(e -> log.error("LLM streaming failed after retries: {}", e.getMessage()));
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

        if (error instanceof WebClientResponseException wcre) {
            int statusCode = wcre.getStatusCode().value();
            String message = wcre.getResponseBodyAsString();
            
            // Try to extract error message from response body
            if (message == null || message.isBlank()) {
                message = wcre.getMessage();
            }

            return new LlmApiException(statusCode, message, error);
        }

        if (error instanceof TimeoutException) {
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
     * Get the underlying OpenAiClient delegate.
     */
    public OpenAiClient getDelegate() {
        return delegate;
    }

    /**
     * Get the retry configuration.
     */
    public RetryConfig getRetryConfig() {
        return retryConfig;
    }
}
