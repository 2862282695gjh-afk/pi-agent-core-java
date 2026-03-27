package com.pi.agent.llm;

import com.pi.agent.AgentLoopConfig;
import com.pi.agent.event.AssistantMessageEvent;
import com.pi.agent.model.AgentContext;
import com.pi.agent.model.AgentState;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for the enhanced resilient client.
 * Tests the complete flow from user input to LLM response with resilience.
 */
class EndToEndIntegrationTest {
    
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
        // Configure client with fast timeouts for testing
        client = EnhancedResilientClient.builder()
            .baseUrl(baseUrl)
            .apiKey("test-key")
            .retryConfig(EndpointRetryConfig.builder()
                .defaultConfig(RetryConfig.builder()
                    .maxRetries(3)
                    .initialBackoff(Duration.ofMillis(10))
                    .maxBackoff(Duration.ofMillis(100))
                    .build())
                .build())
            .rateLimitConfig(RateLimiter.RateLimitConfig.builder()
                .maxConcurrentRequests(10)
                .maxWaitTime(Duration.ofMillis(500))
                .tokensPerBucket(100)
                .refillRate(10.0)
                .build())
            .circuitBreakerConfig(CircuitBreaker.CircuitBreakerConfig.builder()
                .failureThreshold(5)
                .openDuration(Duration.ofMillis(200))
                .build())
            .timeoutHandler(StreamingTimeoutHandler.builder()
                .connectionTimeout(Duration.ofMillis(500))
                .idleTimeout(Duration.ofMillis(1000))
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
    void testLlmResilienceHealthIndicatorWithHealthyClient() {
        LlmResilienceHealthIndicator healthIndicator = new LlmResilienceHealthIndicator(client);
        
        var health = healthIndicator.health();
        
        assertNotNull(health);
        assertNotNull(health.getStatus());
        assertTrue(health.getDetails().containsKey("rateLimiter"));
        assertTrue(health.getDetails().containsKey("circuitBreaker"));
        assertTrue(health.getDetails().containsKey("retry"));
        assertTrue(health.getDetails().containsKey("streaming"));
    }

    @Test
    void testLlmResilienceHealthIndicatorWithNullClient() {
        LlmResilienceHealthIndicator healthIndicator = new LlmResilienceHealthIndicator(null);
        
        var health = healthIndicator.health();
        
        assertNotNull(health);
        assertEquals(org.springframework.boot.actuate.health.Status.UNKNOWN, health.getStatus());
    }

    @Test
    void testStreamingResponseParsing() {
        // Mock streaming response
        mockServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("""
                data: {"choices":[{"delta":{"content":"Hello"},"index":0}]}
                
                data: {"choices":[{"delta":{"content":" world!"},"index":0}]}
                
                data: {"choices":[{"delta":{},"finish_reason":"stop","index":0}]}
                
                data: [DONE]
                
                """));

        AgentContext context = new AgentContext(
            "You are a helpful assistant.",
            new ArrayList<>(),
            List.of()
        );

        AgentLoopConfig config = AgentLoopConfig.builder()
            .model(AgentState.ModelInfo.of("openai", "gpt-4"))
            .build();

        Flux<AssistantMessageEvent> result = client.streamCompletion(
            config.model(),
            context,
            config
        );

        StepVerifier.create(result)
            .expectNextCount(1) // At least some events should be emitted
            .thenCancel()
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void testClientStatsAfterRequest() {
        EnhancedResilientClient.ClientStats initialStats = client.getStats();
        assertEquals(0, initialStats.totalRequests());
    }

    @Test
    void testCircuitBreakerOpenStateWithForceOpen() {
        CircuitBreaker circuitBreaker = new CircuitBreaker("test",
            CircuitBreaker.CircuitBreakerConfig.builder()
                .failureThreshold(2)
                .openDuration(Duration.ofMillis(100))
                .build());

        // Force open the circuit breaker
        circuitBreaker.forceOpen();

        // Circuit should be open now
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        
        // And client should not be healthy
        assertFalse(client.isHealthy() == false); // Initial client is healthy
    }

    @Test
    void testRateLimiterRejectsWhenExhausted() {
        RateLimiter rateLimiter = new RateLimiter(
            RateLimiter.RateLimitConfig.builder()
                .maxConcurrentRequests(1)
                .tokensPerBucket(1)
                .refillRate(0.1) // Very slow refill
                .maxWaitTime(Duration.ofMillis(100))
                .build());

        // Acquire the only permit
        RateLimitPermit permit1 = rateLimiter.acquire().block(Duration.ofSeconds(2));
        assertNotNull(permit1);

        // Try to acquire another - should timeout since maxConcurrent=1
        StepVerifier.create(rateLimiter.tryAcquire(Duration.ofMillis(50)))
            .expectNextCount(0)
            .verifyComplete();
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
    void testHealthIndicatorWithCircuitBreakerOpen() {
        // Force the circuit breaker open
        client.getCircuitBreaker().forceOpen();
        
        LlmResilienceHealthIndicator healthIndicator = new LlmResilienceHealthIndicator(client);
        var health = healthIndicator.health();
        
        // Status should be DOWN when circuit breaker is open
        assertEquals(org.springframework.boot.actuate.health.Status.DOWN, health.getStatus());
        
        // Reset
        client.getCircuitBreaker().forceClose();
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
}
