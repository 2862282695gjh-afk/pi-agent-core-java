package com.pi.agent.llm;

import com.pi.agent.AgentLoopConfig;
import com.pi.agent.event.AssistantMessageEvent;
import com.pi.agent.model.AgentContext;
import com.pi.agent.model.AgentState;
import com.pi.agent.model.message.UserMessage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import reactor.test.scheduler.VirtualTimeScheduler;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for the enhanced resilient client.
 * Tests the complete flow from user input to LLM response with resilience.
 */
class EndToEndIntegrationTest {

    private static MockWebServer mockServer;
    private EnhancedResilientClient client;
    private VirtualTimeScheduler scheduler;

    @BeforeEach
    void setup() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        scheduler = VirtualTimeScheduler.create();
        
        // Configure client with fast timeouts for testing
        client = EnhancedResilientClient.builder()
            .baseUrl(mockServer.url("/v1").toString().replace("/v1", ""))
            .retryConfig(com.pi.agent.llm.RetryConfig.builder()
                .maxRetries(3)
                .initialBackoff(Duration.ofMillis(10))
                .build())
            .rateLimitConfig(com.pi.agent.llm.RateLimiter.RateLimitConfig.builder()
                .maxConcurrentRequests(10)
                .maxWaitTime(Duration.ofMillis(100))
                .build())
            .circuitBreakerConfig(com.pi.agent.llm.CircuitBreaker.CircuitBreakerConfig.builder()
                .failureThreshold(3)
                .openDuration(Duration.ofMillis(100))
                .build())
            .timeoutHandler(StreamingTimeoutHandler.builder()
                .connectionTimeout(Duration.ofMillis(100))
                .idleTimeout(Duration.ofMillis(200))
                .totalTimeout(Duration.ofSeconds(2))
                .build())
            .tracing(LlmTracing.builder().enabled(false).build())
            .build();
    }

    @AfterEach
    void teardown() throws IOException {
        mockServer.shutdown();
        scheduler.dispose();
    }

    
    @Test
    void testEndToEndSuccessFlow() {
        // Mock streaming response
        enqueueStreamingResponse("""
            data: {"choices":[{"delta":{"content":"Hello"},"index":0}]}
            
            data: {"choices":[{"delta":{"content":" world!"},"index":0}]}
            
            data: {"choices":[{"delta":{},"finish_reason":"stop","index":0}]}
            
            data: [DONE]
            
            """);

        AgentContext context = new AgentContext(
            "You are a helpful assistant.",
            new ArrayList<>(),
            List.of()
        );

        AgentLoopConfig config = AgentLoopConfig.builder()
            .model(AgentState.ModelInfo.of("openai", "gpt-4"))
            .build();

        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        Consumer<AgentEvent> eventSink = events::add;

        Flux<AssistantMessageEvent> result = client.streamCompletion(
            config.model(),
            context,
            config
        );

        StepVerifier.create(result)
            .assertNext(events -> assertFalse(events.isEmpty()))
            .verifyComplete();

        // Verify events
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.AgentStart));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.TurnStart));
    }

    
    
    @Test
    void testEndToEndWithRetry() {
        // First request fails
        mockServer.enqueue(new MockResponse()
            .setResponseCode(500)
            .setBody("{\"error\": {\"message\": \"Internal error\"}}"));

        // Second request succeeds
        enqueueStreamingResponse("""
            data: {"choices":[{"delta":{"content":"Retry"},"index":0}]}
            
            data: {"choices":[{"delta":{},"finish_reason":"stop","index":0}]}
            
            data: [DONE]
            
            """);

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
            .verifyComplete();

        // Verify retry happened
        EnhancedResilientClient.ClientStats stats = client.getStats();
        assertTrue(stats.retriedRequests() >= 1);
    }
    
    
    @Test
    void testEndToEndWithCircuitBreaker() {
        // Configure circuit breaker to open quickly
        EnhancedResilientClient fragileClient = EnhancedResilientClient.builder()
            .baseUrl(mockServer.url("/v1").toString().replace("/v1", ""))
            .retryConfig(com.pi.agent.llm.RetryConfig.builder()
                .maxRetries(0)
                .build())
            .circuitBreakerConfig(com.pi.agent.llm.CircuitBreaker.CircuitBreakerConfig.builder()
                .failureThreshold(2)
                .openDuration(Duration.ofMillis(100))
                .build())
            .build();

        // Queue failures
        for (int i = 0; i < 3; i++) {
            mockServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\": {\"message\": \"Error " + i + "\"}}"));
        }

        AgentContext context = new AgentContext(
            "You are a helpful assistant.",
            new ArrayList<>(),
            List.of()
        );

        AgentLoopConfig config = AgentLoopConfig.builder()
            .model(AgentState.ModelInfo.of("openai", "gpt-4"))
            .build();

        // First and second calls should fail
        StepVerifier.create(fragileClient.streamCompletion(config.model(), context, config))
            .expectError()
            .verify();
        
        StepVerifier.create(fragileClient.streamCompletion(config.model(), context, config))
            .expectError()
            .verify();

        // Third call should fail with circuit open
        StepVerifier.create(fragileClient.streamCompletion(config.model(), context, config))
            .expectErrorMatches(e -> 
                e instanceof CircuitBreakerOpenException || 
                e.getMessage().contains("Circuit breaker is OPEN"))
            .verify();
    }
    
    
    @Test
    void testEndToEndWithRateLimit() {
        // Configure strict rate limit
        EnhancedResilientClient limitedClient = EnhancedResilientClient.builder()
            .baseUrl(mockServer.url("/v1").toString().replace("/v1", ""))
            .rateLimitConfig(com.pi.agent.llm.RateLimiter.RateLimitConfig.builder()
                .maxConcurrentRequests(1)
                .maxWaitTime(Duration.ofMillis(50))
                .build())
            .build();

        // Queue slow response
        mockServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"test\"}}]}\n\ndata: [DONE]\n\n")
            .setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS));

        AgentContext context = new AgentContext(
            "You are a helpful assistant.",
            new ArrayList<>(),
            List.of()
        );

        AgentLoopConfig config = AgentLoopConfig.builder()
            .model(AgentState.ModelInfo.of("openai", "gpt-4"))
            .build();

        // First request should succeed eventually
        Flux<AssistantMessageEvent> result = limitedClient.streamCompletion(
            config.model(),
            context,
            config
        );

        StepVerifier.create(result)
            .verifyComplete();

        // Stats should show rate limiting
        EnhancedResilientClient.ClientStats stats = limitedClient.getStats();
        assertTrue(stats.rateLimitStats().totalRequests() >= 1);
    }
    
    
    @Test
    void testHealthIndicatorWithHealthyClient() {
        enqueueStreamingResponse("""
            data: {"choices":[{"delta":{"content":"OK"},"index":0}]}
            
            data: {"choices":[{"delta":{},"finish_reason":"stop","index":0}]}
            
            data: [DONE]
            
            """);

        LlmResilienceHealthIndicator healthIndicator = new LlmResilienceHealthIndicator(client);

        
        Health health = healthIndicator.health();
        
        assertEquals(Status.UP, health.getStatus());
        assertNotNull(health.getDetails());
    }
    
    
    @Test
    void testHealthIndicatorWithUnhealthyClient() {
        // Force circuit breaker open
        client.getCircuitBreaker().forceOpen();
        
        LlmResilienceHealthIndicator healthIndicator = new LlmResilienceHealthIndicator(client);
        
        Health health = healthIndicator.health();
        
        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(health.getDetails().containsKey("circuitBreaker"));
    }
    
    
    @Test
    void testStreamingWithTimeout() {
        // Queue response with delay
        mockServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"start\"}}]}\n\n")
            .setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS));

        AgentContext context = new AgentContext(
            "You are a helpful assistant.",
            new ArrayList<>(),
            List.of()
        );

        AgentLoopConfig config = AgentLoopConfig.builder()
            .model(AgentState.ModelInfo.of("openai", "gpt-4"))
            .build();

        // Should timeout
        StepVerifier.create(client.streamCompletion(config.model(), context, config))
            .expectError(LlmApiException.class)
            .verify(Duration.ofSeconds(5));
    }
    
    
    @Test
    void testToolCallFlow() {
        // Mock tool call response
        enqueueStreamingResponse("""
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_123","function":{"name":"get_weather","arguments":"{\\"location\\":"}}}]},"index":0}]}
            
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":" \\"Beijing\\""}}]},"index":0}]}
            
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]},"index":0}]}
            
            data: {"choices":[{"delta":{},"finish_reason":"tool_calls","index":0}]}
            
            data: [DONE]
            
            """);

        AgentContext context = new AgentContext(
            "You are a helpful assistant.",
            new ArrayList<>(),
            List.of()
        );

        AgentLoopConfig config = AgentLoopConfig.builder()
            .model(AgentState.ModelInfo.of("openai", "gpt-4"))
            .build();

        List<AssistantMessageEvent> events = new CopyOnWriteArrayList<>();
        
        Flux<AssistantMessageEvent> result = client.streamCompletion(
            config.model(),
            context,
            config
        ).doOnNext(events::add);

        StepVerifier.create(result)
            .verifyComplete();

        // Verify tool call events
        assertTrue(events.stream().anyMatch(e -> 
            e instanceof AssistantMessageEvent.ToolCallStart ||
            e instanceof AssistantMessageEvent.ToolCallDelta
        ));
    }
    
    
    @Test
    void testConcurrentRequests() {
        // Configure client for concurrent requests
        int requestCount = 5;
        CountDownLatch latch = new CountDownLatch(requestCount);
        
        for (int i = 0; i < requestCount; i++) {
            enqueueStreamingResponse("""
                data: {"choices":[{"delta":{"content":"Response %d"},"index":0}]}
                
                data: {"choices":[{"delta":{},"finish_reason":"stop","index":0}]}
                
                data: [DONE]
                
                """.formatted(i));
        }

        AgentContext context = new AgentContext(
            "You are a helpful assistant.",
            new ArrayList<>(),
            List.of()
        );

        AgentLoopConfig config = AgentLoopConfig.builder()
            .model(AgentState.ModelInfo.of("openai", "gpt-4"))
            .build();

        // Start concurrent requests
        List<Flux<AssistantMessageEvent>> fluxes = new ArrayList<>();
        for (int i = 0; i < requestCount; i++) {
            fluxes.add(client.streamCompletion(config.model(), context, config));
        }

        // Wait for all to complete
        StepVerifier.create(Flux.merge(fluxes))
            .verifyComplete();

        // Verify rate limiter tracked concurrent requests
        EnhancedResilientClient.ClientStats stats = client.getStats();
        assertTrue(stats.rateLimitStats().totalRequests() >= requestCount);
    }


    
    
    // Helper method to enqueue streaming response
    private void enqueueStreamingResponse(String body) {
        mockServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body));
    }
}
