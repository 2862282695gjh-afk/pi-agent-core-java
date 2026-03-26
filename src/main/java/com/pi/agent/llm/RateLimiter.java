package com.pi.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rate limiter for LLM API calls using token bucket algorithm.
 * Supports both requests-per-second and concurrent request limits.
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final RateLimitConfig config;
    private final Semaphore concurrencySemaphore;
    
    // Token bucket state
    private final AtomicReference<TokenBucketState> bucketState;
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong rejectedRequests = new AtomicLong(0);
    private final AtomicLong lastRefillTime = new AtomicLong(System.nanoTime());

    public RateLimiter(RateLimitConfig config) {
        this.config = config;
        this.concurrencySemaphore = new Semaphore(config.maxConcurrentRequests());
        this.bucketState = new AtomicReference<>(new TokenBucketState(
            config.tokensPerBucket(), Instant.now()
        ));
        
        // Start token refill scheduler
        startTokenRefill();
    }

    /**
     * Acquire a permit to make an API call, waiting if necessary.
     * Returns a Mono that completes when a permit is available.
     */
    public Mono<RateLimitPermit> acquire() {
        return Mono.fromCallable(() -> {
                totalRequests.incrementAndGet();
                
                // Try to acquire concurrency permit
                boolean acquired = concurrencySemaphore.tryAcquire(
                    config.maxWaitTime().toMillis(), 
                    TimeUnit.MILLISECONDS
                );
                
                if (!acquired) {
                    rejectedRequests.incrementAndGet();
                    throw new RateLimitExceededException(
                        "Rate limit exceeded: max concurrent requests (" + 
                        config.maxConcurrentRequests() + ") reached"
                    );
                }
                
                // Wait for token bucket availability
                return waitForToken();
            })
            .subscribeOn(Schedulers.boundedElastic())
            .map(token -> {
                activeRequests.incrementAndGet();
                return new RateLimitPermit(this, token);
            });
    }

    /**
     * Acquire a permit with a timeout, failing fast if not available.
     */
    public Mono<RateLimitPermit> tryAcquire(Duration timeout) {
        return Mono.fromCallable(() -> {
                boolean acquired = concurrencySemaphore.tryAcquire(
                    timeout.toMillis(), TimeUnit.MILLISECONDS
                );
                
                if (!acquired) {
                    return null;
                }
                
                // Try to consume a token without waiting
                return tryConsumeToken();
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(token -> {
                if (token == null) {
                    return Mono.empty();
                }
                activeRequests.incrementAndGet();
                return Mono.just(new RateLimitPermit(this, token));
            });
    }

    /**
     * Release a permit after API call completion.
     */
    void release() {
        concurrencySemaphore.release();
        activeRequests.decrementAndGet();
    }

    /**
     * Wait for a token to be available in the bucket.
     */
    private Token waitForToken() throws InterruptedException {
        long startNanos = System.nanoTime();
        long maxWaitNanos = config.maxWaitTime().toNanos();
        
        while (true) {
            Token token = tryConsumeToken();
            if (token != null) {
                return token;
            }
            
            // Check timeout
            long elapsedNanos = System.nanoTime() - startNanos;
            if (elapsedNanos >= maxWaitNanos) {
                rejectedRequests.incrementAndGet();
                throw new RateLimitExceededException(
                    "Rate limit exceeded: request queue timeout"
                );
            }
            
            // Wait a bit before retrying
            Thread.sleep(config.retryInterval().toMillis());
        }
    }

    /**
     * Try to consume a token from the bucket.
     */
    private Token tryConsumeToken() {
        TokenBucketState current = bucketState.get();
        TokenBucketState updated = consumeToken(current);
        
        if (updated != null && bucketState.compareAndSet(current, updated)) {
            return new Token(updated.timestamp());
        }
        
        return null;
    }

    /**
     * Create a new state with one token consumed, or null if bucket is empty.
     */
    private TokenBucketState consumeToken(TokenBucketState state) {
        if (state.tokens() > 0) {
            return new TokenBucketState(state.tokens() - 1, state.timestamp());
        }
        return null;
    }

    /**
     * Start the token refill background task.
     */
    private void startTokenRefill() {
        Flux.interval(config.refillInterval())
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(tick -> {
                refillTokens();
            });
    }

    /**
     * Refill tokens based on elapsed time.
     */
    private void refillTokens() {
        TokenBucketState current = bucketState.get();
        Instant now = Instant.now();
        Duration elapsed = Duration.between(current.timestamp(), now);
        
        // Calculate tokens to add based on refill rate
        double tokensToAdd = (elapsed.toMillis() / 1000.0) * config.refillRate();
        long newTokens = Math.min(
            config.tokensPerBucket(),
            (long) (current.tokens() + tokensToAdd)
        );
        
        TokenBucketState updated = new TokenBucketState(newTokens, now);
        bucketState.compareAndSet(current, updated);
    }

    /**
     * Get current rate limiter statistics.
     */
    public RateLimitStats getStats() {
        TokenBucketState state = bucketState.get();
        return new RateLimitStats(
            state.tokens(),
            config.tokensPerBucket(),
            activeRequests.get(),
            config.maxConcurrentRequests(),
            totalRequests.get(),
            rejectedRequests.get()
        );
    }

    /**
     * Token bucket state record.
     */
    private record TokenBucketState(long tokens, Instant timestamp) {}

    /**
     * Token record representing a consumed permit.
     */
    public record Token(Instant acquiredAt) {}

    /**
     * Rate limiter configuration.
     */
    public record RateLimitConfig(
        int maxConcurrentRequests,
        int tokensPerBucket,
        double refillRate,
        Duration refillInterval,
        Duration maxWaitTime,
        Duration retryInterval
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private int maxConcurrentRequests = 10;
            private int tokensPerBucket = 100;
            private double refillRate = 10.0; // tokens per second
            private Duration refillInterval = Duration.ofMillis(100);
            private Duration maxWaitTime = Duration.ofSeconds(30);
            private Duration retryInterval = Duration.ofMillis(50);

            public Builder maxConcurrentRequests(int maxConcurrentRequests) {
                this.maxConcurrentRequests = maxConcurrentRequests;
                return this;
            }

            public Builder tokensPerBucket(int tokensPerBucket) {
                this.tokensPerBucket = tokensPerBucket;
                return this;
            }

            public Builder refillRate(double refillRate) {
                this.refillRate = refillRate;
                return this;
            }

            public Builder refillInterval(Duration refillInterval) {
                this.refillInterval = refillInterval;
                return this;
            }

            public Builder maxWaitTime(Duration maxWaitTime) {
                this.maxWaitTime = maxWaitTime;
                return this;
            }

            public Builder retryInterval(Duration retryInterval) {
                this.retryInterval = retryInterval;
                return this;
            }

            public RateLimitConfig build() {
                return new RateLimitConfig(
                    maxConcurrentRequests, tokensPerBucket, refillRate,
                    refillInterval, maxWaitTime, retryInterval
                );
            }
        }
    }

    /**
     * Rate limiter statistics.
     */
    public record RateLimitStats(
        long availableTokens,
        long maxTokens,
        int activeRequests,
        int maxConcurrentRequests,
        long totalRequests,
        long rejectedRequests
    ) {
        public double utilization() {
            return (double) activeRequests / maxConcurrentRequests;
        }

        public double rejectionRate() {
            return totalRequests > 0 ? (double) rejectedRequests / totalRequests : 0.0;
        }
    }
}
