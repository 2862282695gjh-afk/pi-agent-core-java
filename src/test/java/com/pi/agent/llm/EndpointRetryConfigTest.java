package com.pi.agent.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EndpointRetryConfig with per-endpoint settings.
 */
class EndpointRetryConfigTest {

    private EndpointRetryConfig config;

    @BeforeEach
    void setUp() {
        config = EndpointRetryConfig.builder()
            .defaultConfig(RetryConfig.builder()
                .maxRetries(3)
                .initialBackoff(Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(30))
                .build())
            .chatCompletions(RetryConfig.builder()
                .maxRetries(5)
                .initialBackoff(Duration.ofMillis(500))
                .build())
            .embeddings(RetryConfig.builder()
                .maxRetries(2)
                .initialBackoff(Duration.ofSeconds(2))
                .build())
            .retryBudget(10, Duration.ofSeconds(5), 2)
            .fixedJitter(0.2)
            .build();
    }

    @Test
    @DisplayName("Should return default config for unknown endpoints")
    void testDefaultConfigForUnknownEndpoint() {
        RetryConfig unknownConfig = config.getConfigForEndpoint("unknown.endpoint");
        
        assertEquals(3, unknownConfig.maxRetries());
        assertEquals(Duration.ofSeconds(1), unknownConfig.initialBackoff());
        assertEquals(Duration.ofSeconds(30), unknownConfig.maxBackoff());
    }

    @Test
    @DisplayName("Should return specific config for chat completions")
    void testChatCompletionsConfig() {
        RetryConfig chatConfig = config.getConfigForEndpoint("chat.completions");
        
        assertEquals(5, chatConfig.maxRetries());
        assertEquals(Duration.ofMillis(500), chatConfig.initialBackoff());
    }

    @Test
    @DisplayName("Should return specific config for embeddings")
    void testEmbeddingsConfig() {
        RetryConfig embeddingsConfig = config.getConfigForEndpoint("embeddings");
        
        assertEquals(2, embeddingsConfig.maxRetries());
        assertEquals(Duration.ofSeconds(2), embeddingsConfig.initialBackoff());
    }

    @Test
    @DisplayName("Should track retry budget")
    void testRetryBudget() {
        // Should have budget initially
        assertTrue(config.hasRetryBudget());
        assertTrue(config.hasRetryBudget());

        // Check stats
        RetryBudget.RetryBudgetStats stats = config.getBudgetStats();
        assertEquals(10, stats.maxTokens());
        assertEquals(8, stats.availableTokens()); // 10 - 2 = 8
        assertEquals(2, stats.totalTokensConsumed());
    }

    @Test
    @DisplayName("Should return token to budget")
    void testReturnRetryBudget() {
        config.hasRetryBudget();
        config.hasRetryBudget();

        int beforeReturn = config.getBudgetStats().availableTokens();
        config.returnRetryBudget();
        int afterReturn = config.getBudgetStats().availableTokens();

        assertEquals(1, afterReturn - beforeReturn);
    }

    @Test
    @DisplayName("Should calculate jittered backoff")
    void testCalculateBackoff() {
        // First attempt (attempt = 1)
        Duration backoff1 = config.calculateBackoff("chat.completions", 1);
        // Base is 500ms, with jitter, should be roughly in [400, 600] range
        assertTrue(backoff1.toMillis() >= 400 && backoff1.toMillis() <= 600,
            "Backoff should be in range [400, 600] but was " + backoff1.toMillis());

        // Second attempt (attempt = 2)
        Duration backoff2 = config.calculateBackoff("chat.completions", 2);
        // Base is 500 * 2 = 1000ms, with jitter roughly [800, 1200], capped at 30s
        assertTrue(backoff2.toMillis() >= 800 && backoff2.toMillis() <= 1200,
            "Backoff should be in range [800, 1200] but was " + backoff2.toMillis());

        // High attempt should cap at max backoff
        Duration backoff10 = config.calculateBackoff("chat.completions", 10);
        assertTrue(backoff10.toMillis() <= 30000,
            "Backoff should be capped at 30s but was " + backoff10.toMillis());
    }

    @Test
    @DisplayName("Should create default config")
    void testDefaultConfig() {
        EndpointRetryConfig defaultConfig = new EndpointRetryConfig();

        assertNotNull(defaultConfig.defaultConfig());
        assertNotNull(defaultConfig.retryBudget());
        assertNotNull(defaultConfig.jitterStrategy());
    }

    @Test
    @DisplayName("Should support all jitter strategies via builder")
    void testJitterStrategies() {
        // Fixed
        EndpointRetryConfig fixed = EndpointRetryConfig.builder()
            .fixedJitter(0.3)
            .build();
        assertTrue(fixed.jitterStrategy() instanceof JitterStrategy.FixedJitter);

        // Equal
        EndpointRetryConfig equal = EndpointRetryConfig.builder()
            .equalJitter()
            .build();
        assertTrue(equal.jitterStrategy() instanceof JitterStrategy.EqualJitter);

        // Full
        EndpointRetryConfig full = EndpointRetryConfig.builder()
            .fullJitter()
            .build();
        assertTrue(full.jitterStrategy() instanceof JitterStrategy.FullJitter);

        // Decorrelated
        EndpointRetryConfig decorrelated = EndpointRetryConfig.builder()
            .decorrelatedJitter(Duration.ofMillis(100), Duration.ofSeconds(30))
            .build();
        assertTrue(decorrelated.jitterStrategy() instanceof JitterStrategy.DecorrelatedJitter);
    }

    @Test
    @DisplayName("Should use default config backoff settings")
    void testDefaultConfigBackoff() {
        Duration backoff = config.calculateBackoff("models", 1);
        // Default initial backoff is 1 second with 20% jitter
        assertTrue(backoff.toMillis() >= 800 && backoff.toMillis() <= 1200,
            "Default backoff should be in range [800, 1200] but was " + backoff.toMillis());
    }
}
