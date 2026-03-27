package com.pi.agent.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RetryBudget token bucket implementation.
 */
class RetryBudgetTest {

    private RetryBudget budget;

    @BeforeEach
    void setUp() {
        budget = RetryBudget.builder()
            .maxTokens(5)
            .replenishPeriod(Duration.ofMillis(50))
            .tokensPerReplenish(1)
            .build();
    }

    @Test
    @DisplayName("Should allow consuming tokens within budget")
    void testConsumeWithinBudget() {
        // Should have 5 tokens initially
        assertTrue(budget.tryConsume());
        assertTrue(budget.tryConsume());
        assertTrue(budget.tryConsume());
        assertTrue(budget.tryConsume());
        assertTrue(budget.tryConsume());
        
        // Should be exhausted now
        assertEquals(0, budget.getAvailableTokens());
        assertFalse(budget.tryConsume());
    }

    @Test
    @DisplayName("Should track consumed tokens in stats")
    void testStatsTracking() {
        budget.tryConsume();
        budget.tryConsume();
        budget.tryConsume();
        
        RetryBudget.RetryBudgetStats stats = budget.getStats();
        assertEquals(5, stats.maxTokens());
        assertEquals(2, stats.availableTokens()); // 5 - 3 = 2
        assertEquals(3, stats.totalTokensConsumed());
        assertEquals(0, stats.totalRequestsRejected());
        assertEquals(0.6, stats.utilization(), 0.01);
    }

    @Test
    @DisplayName("Should reject requests when budget exhausted")
    void testRejectOnExhaustedBudget() {
        // Consume all tokens
        for (int i = 0; i < 5; i++) {
            assertTrue(budget.tryConsume());
        }
        
        // Should be rejected
        assertFalse(budget.tryConsume());
        
        RetryBudget.RetryBudgetStats stats = budget.getStats();
        assertEquals(1, stats.totalRequestsRejected());
    }

    @Test
    @DisplayName("Should allow returning tokens to budget")
    void testReturnToken() {
        budget.tryConsume();
        budget.tryConsume();
        budget.tryConsume();
        
        assertEquals(2, budget.getAvailableTokens());
        
        // Return a token
        budget.returnToken();
        assertEquals(3, budget.getAvailableTokens());
        
        // Return another token
        budget.returnToken();
        assertEquals(4, budget.getAvailableTokens());
    }

    @Test
    @DisplayName("Should not exceed max tokens when returning")
    void testReturnDoesNotExceedMax() {
        // Consume and return multiple times
        budget.tryConsume();
        budget.returnToken();
        budget.returnToken();
        budget.returnToken();
        
        // Should still be at max
        assertEquals(5, budget.getAvailableTokens());
    }

    @Test
    @DisplayName("Should replenish tokens over time")
    void testReplenishment() throws InterruptedException {
        // Consume all tokens
        for (int i = 0; i < 5; i++) {
            budget.tryConsume();
        }
        assertEquals(0, budget.getAvailableTokens());
        
        // Wait for replenishment period
        Thread.sleep(60);
        
        // Should have replenished 1 token
        assertEquals(1, budget.getAvailableTokens());
        
        // Should be able to consume again
        assertTrue(budget.tryConsume());
    }

    @Test
    @DisplayName("Should reset budget to full capacity")
    void testReset() {
        budget.tryConsume();
        budget.tryConsume();
        budget.tryConsume();
        
        assertEquals(2, budget.getAvailableTokens());
        
        budget.reset();
        assertEquals(5, budget.getAvailableTokens());
    }

    @Test
    @DisplayName("Should calculate rejection rate correctly")
    void testRejectionRate() {
        // 5 successful, 2 rejected
        for (int i = 0; i < 5; i++) {
            budget.tryConsume();
        }
        budget.tryConsume();
        budget.tryConsume();
        
        RetryBudget.RetryBudgetStats stats = budget.getStats();
        assertEquals(2.0/7.0, stats.rejectionRate(), 0.01);
    }

    @Test
    @DisplayName("Should support concurrent access")
    void testConcurrentAccess() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 100;
        Thread[] threads = new Thread[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    if (budget.tryConsume()) {
                        // Simulate some work
                        budget.returnToken();
                    }
                }
            });
        }
        
        for (Thread t : threads) {
            t.start();
        }
        
        for (Thread t : threads) {
            t.join();
        }
        
        // Budget should be valid after concurrent access
        RetryBudget.RetryBudgetStats stats = budget.getStats();
        assertTrue(stats.availableTokens() >= 0);
        assertTrue(stats.availableTokens() <= stats.maxTokens());
    }
}
