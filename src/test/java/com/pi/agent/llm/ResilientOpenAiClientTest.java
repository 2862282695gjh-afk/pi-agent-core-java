package com.pi.agent.llm;

import com.pi.agent.AgentLoopConfig;
import com.pi.agent.event.AssistantMessageEvent;
import com.pi.agent.model.AgentContext;
import com.pi.agent.model.AgentState;
import com.pi.agent.model.message.UserMessage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ResilientOpenAiClient.
 * Tests the combination of rate limiting, circuit breaker, and retry logic.
 */
class ResilientOpenAiClientTest {

    private MockWebServer mockServer;
    private ResilientOpenAiClient client;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        
        String baseUrl = mockServer.url("/v1").toString();
        
        // Configure with relaxed settings for testing
        RetryConfig retryConfig = RetryConfig.builder()
            .maxRetries(2)
            .initialBackoff(Duration.ofMillis(10))
            .maxBackoff(Duration.ofMillis(50))
            .backoffMultiplier(1.5)
            .retryOnRateLimit(true)
            .retryOnServerError(true)
            .retryOnTimeout(true)
            .build();
        
        RateLimiter.RateLimitConfig rateLimitConfig = RateLimiter.RateLimitConfig.builder()
            .maxConcurrentRequests(10)
            .tokensPerBucket(100)
            .refillRate(50.0)
            .refillInterval(Duration.ofMillis(50))
            .maxWaitTime(Duration.ofSeconds(5))
            .retryInterval(Duration.ofMillis(5))
            .build();
        
        CircuitBreaker.CircuitBreakerConfig circuitBreakerConfig = 
            CircuitBreaker.CircuitBreakerConfig.builder()
                .failureThreshold(5)
                .openDuration(Duration.ofMillis(200))
                .halfOpenMaxRequests(2)
                .halfOpenSuccessThreshold(2)
                .checkInterval(Duration.ofMillis(20))
                .build();
        
        client = new ResilientOpenAiClient(
            new OpenAiClient(baseUrl, "test-api-key"),
            retryConfig,
            rateLimitConfig,
            circuitBreakerConfig
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("Should stream completion successfully")
    void shouldStreamCompletionSuccessfully() {
        // Mock successful streaming response
        mockServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n" +
                     "data: [DONE]\n\n"));
        
        AgentState.ModelInfo model = new AgentState.ModelInfo("chat", "openai", "gpt-4");
        AgentContext context = AgentContext.builder()
            .messages(List.of(UserMessage.of("test")))
            .build();
        AgentLoopConfig config = AgentLoopConfig.builder().build();
        
        Flux<AssistantMessageEvent> events = client.streamCompletionResilient(model, context, config);
        
        // Should complete without error
        StepVerifier.create(events)
            .expectNextCount(1) // At least one event
            .verifyComplete();
    }

    @Test
    @DisplayName("Should handle error responses")
    void shouldHandleErrorResponses() {
        mockServer.enqueue(new MockResponse()
            .setResponseCode(500)
            .setBody("{\"error\": {\"message\": \"Internal server error\"}}"));
        
        AgentState.ModelInfo model = new AgentState.ModelInfo("chat", "openai", "gpt-4");
        AgentContext context = AgentContext.builder()
            .messages(List.of(UserMessage.of("test")))
            .build();
        AgentLoopConfig config = AgentLoopConfig.builder().build();
        
        Flux<AssistantMessageEvent> events = client.streamCompletionResilient(model, context, config);
        
        // Should receive an error event
        StepVerifier.create(events)
            .expectNextMatches(event -> event instanceof AssistantMessageEvent.Error)
            .verifyComplete();
    }

    @Test
    @DisplayName("Should provide resilience metrics")
    void shouldProvideResilienceMetrics() {
        ResilientOpenAiClient.ResilienceMetrics metrics = client.getMetrics();
        
        assertNotNull(metrics);
        assertNotNull(metrics.rateLimitStats());
        assertNotNull(metrics.circuitBreakerStats());
        assertTrue(metrics.isHealthy());
    }

    @Test
    @DisplayName("Should reject requests when circuit is open")
    void shouldRejectRequestsWhenCircuitIsOpen() {
        // Force open the circuit
        client.getCircuitBreaker().forceOpen();
        
        AgentState.ModelInfo model = new AgentState.ModelInfo("chat", "openai", "gpt-4");
        AgentContext context = AgentContext.builder()
            .messages(List.of(UserMessage.of("test")))
            .build();
        AgentLoopConfig config = AgentLoopConfig.builder().build();
        
        Flux<AssistantMessageEvent> events = client.streamCompletionResilient(model, context, config);
        
        StepVerifier.create(events)
            .expectError(CircuitBreakerOpenException.class)
            .verify(Duration.ofSeconds(5));
        
        // No request should have been made
        assertEquals(0, mockServer.getRequestCount());
    }

    @Test
    @DisplayName("Should expose rate limiter for monitoring")
    void shouldExposeRateLimiterForMonitoring() {
        RateLimiter limiter = client.getRateLimiter();
        assertNotNull(limiter);
        assertNotNull(limiter.getStats());
    }

    @Test
    @DisplayName("Should expose circuit breaker for monitoring")
    void shouldExposeCircuitBreakerForMonitoring() {
        CircuitBreaker breaker = client.getCircuitBreaker();
        assertNotNull(breaker);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    @DisplayName("Should expose underlying delegate client")
    void shouldExposeUnderlyingDelegateClient() {
        OpenAiClient delegate = client.getDelegate();
        assertNotNull(delegate);
    }
}
