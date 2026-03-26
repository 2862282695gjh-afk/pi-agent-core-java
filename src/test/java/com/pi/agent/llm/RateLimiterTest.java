package com.pi.agent.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RateLimiter functionality.
 */
class RateLimiterTest {

    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        RateLimiter.RateLimitConfig config = RateLimiter.RateLimitConfig.builder()
            .maxConcurrentRequests(2)
            .tokensPerBucket(10)
            .refillRate(5.0) // 5 tokens per second
            .refillInterval(Duration.ofMillis(100))
            .maxWaitTime(Duration.ofMillis(500))
            .retryInterval(Duration.ofMillis(10))
            .build();
        
        rateLimiter = new RateLimiter(config);
    }

    @Test
    @DisplayName("Should acquire permit successfully")
    void shouldAcquirePermit() {
        Mono<RateLimitPermit> permitMono = rateLimiter.acquire();
        
        StepVerifier.create(permitMono)
            .assertNext(permit -> {
                assertNotNull(permit);
                assertFalse(permit.isReleased());
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("Should release permit properly")
    void shouldReleasePermit() {
        RateLimitPermit permit = rateLimiter.acquire().block();
        assertNotNull(permit);
        assertFalse(permit.isReleased());
        
        permit.release();
        assertTrue(permit.isReleased());
        
        // Should be able to acquire again after release
        RateLimitPermit permit2 = rateLimiter.acquire().block();
        assertNotNull(permit2);
        permit2.release();
    }

    @Test
    @DisplayName("Should allow max concurrent requests")
    void shouldAllowMaxConcurrentRequests() {
        RateLimitPermit permit1 = rateLimiter.acquire().block();
        RateLimitPermit permit2 = rateLimiter.acquire().block();
        
        assertNotNull(permit1);
        assertNotNull(permit2);
        
        // Both should be active
        RateLimiter.RateLimitStats stats = rateLimiter.getStats();
        assertEquals(2, stats.activeRequests());
        
        permit1.release();
        permit2.release();
    }

    @Test
    @DisplayName("Should reject request when max concurrent exceeded")
    void shouldRejectWhenMaxConcurrentExceeded() {
        RateLimiter.RateLimitConfig strictConfig = RateLimiter.RateLimitConfig.builder()
            .maxConcurrentRequests(1)
            .tokensPerBucket(10)
            .refillRate(5.0)
            .refillInterval(Duration.ofMillis(100))
            .maxWaitTime(Duration.ofMillis(100))
            .retryInterval(Duration.ofMillis(10))
            .build();
        
        RateLimiter strictLimiter = new RateLimiter(strictConfig);
        
        // Acquire the only permit
        RateLimitPermit permit1 = strictLimiter.acquire().block();
        assertNotNull(permit1);
        
        // Should fail to acquire second permit
        StepVerifier.create(strictLimiter.acquire())
            .expectError(RateLimitExceededException.class)
            .verify(Duration.ofSeconds(2));
        
        permit1.release();
    }

    @Test
    @DisplayName("Should track statistics correctly")
    void shouldTrackStatistics() {
        RateLimiter.RateLimitStats initialStats = rateLimiter.getStats();
        assertEquals(0, initialStats.totalRequests());
        assertEquals(0, initialStats.activeRequests());
        
        // Acquire a permit
        RateLimitPermit permit = rateLimiter.acquire().block();
        
        RateLimiter.RateLimitStats afterAcquireStats = rateLimiter.getStats();
        assertEquals(1, afterAcquireStats.totalRequests());
        assertEquals(1, afterAcquireStats.activeRequests());
        
        // Release permit
        permit.release();
        
        RateLimiter.RateLimitStats afterReleaseStats = rateLimiter.getStats();
        assertEquals(0, afterReleaseStats.activeRequests());
    }

    @Test
    @DisplayName("Should support tryAcquire with timeout")
    void shouldSupportTryAcquireWithTimeout() {
        // Should succeed immediately
        StepVerifier.create(rateLimiter.tryAcquire(Duration.ofSeconds(1)))
            .assertNext(permit -> {
                assertNotNull(permit);
                permit.release();
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("TryAcquire should return empty when not available")
    void tryAcquireShouldReturnEmptyWhenNotAvailable() {
        RateLimiter.RateLimitConfig strictConfig = RateLimiter.RateLimitConfig.builder()
            .maxConcurrentRequests(1)
            .tokensPerBucket(1)
            .refillRate(1.0)
            .refillInterval(Duration.ofSeconds(1))
            .maxWaitTime(Duration.ofSeconds(1))
            .retryInterval(Duration.ofMillis(10))
            .build();
        
        RateLimiter strictLimiter = new RateLimiter(strictConfig);
        
        // Acquire the only permit
        RateLimitPermit permit = strictLimiter.acquire().block();
        assertNotNull(permit);
        
        // Try to acquire with very short timeout - should return empty
        StepVerifier.create(strictLimiter.tryAcquire(Duration.ofMillis(10)))
            .verifyComplete(); // Empty mono
        
        permit.release();
    }

    @Test
    @DisplayName("Permit should track elapsed time")
    void permitShouldTrackElapsedTime() throws InterruptedException {
        RateLimitPermit permit = rateLimiter.acquire().block();
        assertNotNull(permit);
        
        Thread.sleep(50);
        
        Duration elapsed = permit.elapsedTime();
        assertTrue(elapsed.toMillis() >= 50);
        
        permit.release();
    }

    @Test
    @DisplayName("Permit should be closeable with try-with-resources")
    void permitShouldBeCloseableWithTryWithResources() {
        try (RateLimitPermit permit = rateLimiter.acquire().block()) {
            assertNotNull(permit);
            assertFalse(permit.isReleased());
        }
        
        // After try-with-resources, permit should be released
        RateLimiter.RateLimitStats stats = rateLimiter.getStats();
        assertEquals(0, stats.activeRequests());
    }
}
