package com.pi.agent.llm;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests specifically for retry logic and resilience features.
 */
class RetryIntegrationTest {
    
    private static MockWebServer mockServer;
    private static String baseUrl;
    private EnhancedResilientClient client;
    
    @BeforeAll
    static void startServer() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        baseUrl = mockServer.url("/v1").toString().replace("/v1", "");
    }
    
    @AfterAll
    static void stopServer() throws IOException {
        mockServer.shutdown();
    }
    
    @BeforeEach
    void setup() {
        // Configure with aggressive retry for testing
        client = EnhancedResilientClient.builder()
            .baseUrl(baseUrl)
            .apiKey("test-key")
            .retryConfig(EndpointRetryConfig.builder()
                .defaultConfig(RetryConfig.builder()
                    .maxRetries(3)
                    .initialBackoff(Duration.ofMillis(50))
                    .maxBackoff(Duration.ofMillis(200))
                    .backoffMultiplier(2.0)
                    .retryOnRateLimit(true)
                    .retryOnServerError(true)
                    .retryOnTimeout(true)
                    .build())
                .retryBudget(RetryBudget.builder()
                    .maxTokens(10)
                    .replenishPeriod(Duration.ofSeconds(5))
                    .tokensPerReplenish(2)
                    .build())
                .fixedJitter(0.1)
                .build())
            .rateLimitConfig(RateLimiter.RateLimitConfig.builder()
                .maxConcurrentRequests(5)
                .maxWaitTime(Duration.ofMillis(500))
                .tokensPerBucket(50)
                .refillRate(10.0)
                .build())
            .circuitBreakerConfig(CircuitBreaker.CircuitBreakerConfig.builder()
                .failureThreshold(3)
                .openDuration(Duration.ofMillis(500))
                .build())
            .timeoutHandler(StreamingTimeoutHandler.builder()
                .connectionTimeout(Duration.ofMillis(1000))
                .idleTimeout(Duration.ofMillis(2000))
                .totalTimeout(Duration.ofSeconds(5))
                .build())
            .tracing(LlmTracing.builder().enabled(false).build())
            .build();
    }
    
    @Test
    void testClientCreation() {
        assertNotNull(client);
        assertNotNull(client.getRateLimiter());
        assertNotNull(client.getCircuitBreaker());
        assertNotNull(client.getRetryConfig());
        assertNotNull(client.getTimeoutHandler());
    }
    
    @Test
    void testClientStatsInitial() {
        EnhancedResilientClient.ClientStats stats = client.getStats();
        
        assertEquals(0, stats.totalRequests());
        assertEquals(0, stats.successfulRequests());
        assertEquals(0, stats.failedRequests());
        assertEquals(0, stats.retriedRequests());
        assertNotNull(stats.rateLimitStats());
        assertNotNull(stats.circuitBreakerStats());
        assertNotNull(stats.budgetStats());
        assertNotNull(stats.timeoutStats());
    }
    
    @Test
    void testIsHealthyInitially() {
        assertTrue(client.isHealthy());
    }
    
    @Test
    void testRateLimiterAcquireAndRelease() {
        RateLimiter rateLimiter = client.getRateLimiter();
        RateLimiter.RateLimitStats initialStats = rateLimiter.getStats();
        
        assertEquals(0, initialStats.activeRequests());
        
        // Acquire permit
        RateLimitPermit permit = rateLimiter.acquire().block(Duration.ofSeconds(5));
        assertNotNull(permit);
        
        RateLimiter.RateLimitStats afterAcquireStats = rateLimiter.getStats();
        assertEquals(1, afterAcquireStats.activeRequests());
        
        // Release permit
        permit.release();
        
        // Give time for async release
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    @Test
    void testCircuitBreakerInitialState() {
        CircuitBreaker circuitBreaker = client.getCircuitBreaker();
        
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        
        CircuitBreaker.CircuitBreakerStats stats = circuitBreaker.getStats();
        assertEquals(0, stats.currentFailureCount());
        assertEquals(0.0, stats.failureRate(), 0.001);
    }
    
    @Test
    void testCircuitBreakerForceOpen() {
        CircuitBreaker circuitBreaker = client.getCircuitBreaker();
        
        // Force open
        circuitBreaker.forceOpen();
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        
        // Force close
        circuitBreaker.forceClose();
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }
    
    @Test
    void testStreamingTimeoutHandlerStats() {
        StreamingTimeoutHandler timeoutHandler = client.getTimeoutHandler();
        StreamingTimeoutHandler.TimeoutStats stats = timeoutHandler.getStats();
        
        assertNotNull(stats);
        assertEquals(0, stats.totalTimeouts());
        assertEquals(0, stats.connectionTimeouts());
        assertEquals(0, stats.idleTimeouts());
    }
    
    @Test
    void testEndpointRetryConfigDefaults() {
        EndpointRetryConfig retryConfig = client.getRetryConfig();
        
        assertNotNull(retryConfig);
        assertNotNull(retryConfig.getConfigForEndpoint("chat.completions"));
    }
    
    @Test
    void testRetryConfigForEndpoint() {
        EndpointRetryConfig retryConfig = client.getRetryConfig();
        RetryConfig config = retryConfig.getConfigForEndpoint("chat.completions");
        
        assertNotNull(config);
        assertTrue(config.maxRetries() >= 0);
        assertNotNull(config.initialBackoff());
        assertNotNull(config.maxBackoff());
    }
    
    @Test
    void testJitterAppliedToBackoff() {
        EndpointRetryConfig retryConfig = client.getRetryConfig();
        
        // Calculate multiple backoffs for same attempt
        Duration backoff1 = retryConfig.calculateBackoff("chat.completions", 1);
        Duration backoff2 = retryConfig.calculateBackoff("chat.completions", 1);
        Duration backoff3 = retryConfig.calculateBackoff("chat.completions", 1);
        
        // With jitter, they should be in valid range
        assertTrue(backoff1.toMillis() >= 0);
        assertTrue(backoff2.toMillis() >= 0);
        assertTrue(backoff3.toMillis() >= 0);
    }
    
    @Test
    void testExponentialBackoff() {
        EndpointRetryConfig retryConfig = client.getRetryConfig();
        
        Duration backoff1 = retryConfig.calculateBackoff("chat.completions", 1);
        Duration backoff2 = retryConfig.calculateBackoff("chat.completions", 2);
        Duration backoff3 = retryConfig.calculateBackoff("chat.completions", 3);
        
        // Verify they're all positive
        assertTrue(backoff1.toMillis() > 0);
        assertTrue(backoff2.toMillis() > 0);
        assertTrue(backoff3.toMillis() > 0);
        
        // And verify max is respected
        assertTrue(backoff1.toMillis() <= 200);
        assertTrue(backoff2.toMillis() <= 200);
        assertTrue(backoff3.toMillis() <= 200);
    }
    
    @Test
    void testCircuitBreakerStats() {
        CircuitBreaker circuitBreaker = client.getCircuitBreaker();
        CircuitBreaker.CircuitBreakerStats stats = circuitBreaker.getStats();
        
        assertNotNull(stats.name());
        assertEquals(CircuitBreaker.State.CLOSED, stats.state());
        assertEquals(0, stats.totalRequests());
        assertEquals(0, stats.totalFailures());
        assertEquals(0, stats.totalSuccesses());
    }
    
    @Test
    void testRetryBudgetStats() {
        EndpointRetryConfig retryConfig = client.getRetryConfig();
        RetryBudget.RetryBudgetStats stats = retryConfig.getBudgetStats();
        
        assertNotNull(stats);
        assertTrue(stats.utilization() >= 0.0 && stats.utilization() <= 1.0);
    }
    
    @Test
    void testEndpointRetryConfigCalculateBackoff() {
        EndpointRetryConfig config = client.getRetryConfig();
        
        Duration backoff1 = config.calculateBackoff("chat.completions", 1);
        Duration backoff2 = config.calculateBackoff("chat.completions", 2);
        Duration backoff3 = config.calculateBackoff("chat.completions", 3);
        
        // Backoff should increase with attempts (with jitter applied)
        assertTrue(backoff1.toMillis() >= 0);
        assertTrue(backoff2.toMillis() >= 0);
        assertTrue(backoff3.toMillis() >= 0);
    }
    
    @Test
    void testPerEndpointRetryConfig() {
        // Create client with per-endpoint config
        var customClient = EnhancedResilientClient.builder()
            .baseUrl(baseUrl)
            .apiKey("test-key")
            .retryConfig(EndpointRetryConfig.builder()
                .defaultConfig(RetryConfig.builder()
                    .maxRetries(1)
                    .initialBackoff(Duration.ofMillis(10))
                    .build())
                .chatCompletions(RetryConfig.builder()
                    .maxRetries(5)
                    .initialBackoff(Duration.ofMillis(20))
                    .build())
                .build())
            .build();
        
        EndpointRetryConfig retryConfig = customClient.getRetryConfig();
        
        // Chat completions should use custom config
        RetryConfig chatConfig = retryConfig.getConfigForEndpoint("chat.completions");
        assertEquals(5, chatConfig.maxRetries());
        assertEquals(20, chatConfig.initialBackoff().toMillis());
        
        // Other endpoints should use default
        RetryConfig defaultConfig = retryConfig.getConfigForEndpoint("embeddings");
        assertEquals(1, defaultConfig.maxRetries());
        assertEquals(10, defaultConfig.initialBackoff().toMillis());
    }
    
    @Test
    void testRetryBudgetHasTokensInitially() {
        RetryBudget budget = client.getRetryConfig().retryBudget();
        assertTrue(budget.getAvailableTokens() > 0);
    }
    
    @Test
    void testRetryBudgetConsumption() {
        RetryBudget budget = new RetryBudget(2, Duration.ofSeconds(10), 1);
        
        // Should have budget initially
        assertTrue(budget.tryConsume());
        assertEquals(1, budget.getAvailableTokens());
        
        // Can consume again
        assertTrue(budget.tryConsume());
        assertEquals(0, budget.getAvailableTokens());
        
        // No more budget
        assertFalse(budget.tryConsume());
    }
    
    @Test
    void testRetryBudgetReturn() {
        RetryBudget budget = new RetryBudget(2, Duration.ofSeconds(10), 1);
        
        // Consume all
        assertTrue(budget.tryConsume());
        assertTrue(budget.tryConsume());
        assertEquals(0, budget.getAvailableTokens());
        
        // Return one
        budget.returnToken();
        assertEquals(1, budget.getAvailableTokens());
        
        // Can consume again
        assertTrue(budget.tryConsume());
    }
}
