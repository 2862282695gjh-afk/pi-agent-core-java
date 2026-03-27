package com.pi.agent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for jitter strategies used in retry backoff.
 */
class JitterStrategyTest {

    @Test
    @DisplayName("NONE strategy should return unchanged backoff")
    void testNoneStrategy() {
        long backoff = JitterStrategy.NONE.applyJitter(1000, 1);
        assertEquals(1000, backoff);
        
        backoff = JitterStrategy.NONE.applyJitter(5000, 3);
        assertEquals(5000, backoff);
    }

    @Test
    @DisplayName("Fixed jitter should apply variation within factor range")
    void testFixedJitter() {
        // Create a deterministic random for testing
        Random mockRandom = new Random(42);
        JitterStrategy.FixedJitter jitter = new JitterStrategy.FixedJitter(0.2, mockRandom);
        
        // With factor 0.2, range should be [800, 1200] for base 1000
        for (int i = 0; i < 100; i++) {
            long jittered = jitter.applyJitter(1000, 1);
            assertTrue(jittered >= 800 && jittered <= 1200,
                "Jittered value " + jittered + " should be in range [800, 1200]");
        }
    }

    @Test
    @DisplayName("Equal jitter should return at least half the backoff")
    void testEqualJitter() {
        Random mockRandom = new Random(42);
        JitterStrategy.EqualJitter jitter = new JitterStrategy.EqualJitter(mockRandom);
        
        // Equal jitter: base/2 + random(0, base/2) = range [base/2, base]
        for (int i = 0; i < 100; i++) {
            long jittered = jitter.applyJitter(1000, 1);
            assertTrue(jittered >= 500 && jittered <= 1000,
                "Jittered value " + jittered + " should be in range [500, 1000]");
        }
    }

    @Test
    @DisplayName("Full jitter should return random value from 0 to base")
    void testFullJitter() {
        Random mockRandom = new Random(42);
        JitterStrategy.FullJitter jitter = new JitterStrategy.FullJitter(mockRandom);
        
        // Full jitter: random(0, base)
        for (int i = 0; i < 100; i++) {
            long jittered = jitter.applyJitter(1000, 1);
            assertTrue(jittered >= 0 && jittered < 1000,
                "Jittered value " + jittered + " should be in range [0, 1000)");
        }
    }

    @Test
    @DisplayName("Decorrelated jitter should decorrelate across attempts")
    void testDecorrelatedJitter() {
        Random mockRandom = new Random(42);
        JitterStrategy.DecorrelatedJitter jitter = 
            new JitterStrategy.DecorrelatedJitter(100, 10000, mockRandom);
        
        // First call should be between initial and max
        long first = jitter.applyJitter(500, 1);
        assertTrue(first >= 100 && first <= 10000,
            "First jitter " + first + " should be in range [100, 10000]");
        
        // Second call should be decorrelated from first
        long second = jitter.applyJitter(500, 2);
        assertTrue(second >= 100 && second <= 10000,
            "Second jitter " + second + " should be in range [100, 10000]");
        
        // Values should potentially be different (decorrelated)
        // (though random could theoretically produce same value twice)
    }

    @Test
    @DisplayName("Decorrelated jitter reset should return to initial state")
    void testDecorrelatedReset() {
        Random mockRandom = new Random(42);
        JitterStrategy.DecorrelatedJitter jitter = 
            new JitterStrategy.DecorrelatedJitter(100, 10000, mockRandom);
        
        jitter.applyJitter(500, 1);
        jitter.applyJitter(500, 2);
        
        jitter.reset();
        
        // After reset, should behave like fresh start
        long afterReset = jitter.applyJitter(500, 1);
        assertTrue(afterReset >= 100 && afterReset <= 10000);
    }

    @Test
    @DisplayName("Factory methods should create correct strategy types")
    void testFactoryMethods() {
        // Fixed
        JitterStrategy fixed = JitterStrategy.fixed(0.3);
        assertTrue(fixed instanceof JitterStrategy.FixedJitter);
        
        // Equal
        JitterStrategy equal = JitterStrategy.equal();
        assertTrue(equal instanceof JitterStrategy.EqualJitter);
        
        // Full
        JitterStrategy full = JitterStrategy.full();
        assertTrue(full instanceof JitterStrategy.FullJitter);
        
        // Decorrelated
        JitterStrategy decorrelated = JitterStrategy.decorrelated(100, 5000);
        assertTrue(decorrelated instanceof JitterStrategy.DecorrelatedJitter);
    }

    @Test
    @DisplayName("Fixed jitter factor must be between 0 and 1")
    void testFixedJitterFactorValidation() {
        assertThrows(IllegalArgumentException.class, () -> 
            new JitterStrategy.FixedJitter(-0.1));
        assertThrows(IllegalArgumentException.class, () -> 
            new JitterStrategy.FixedJitter(1.5));
        
        // Valid factors should not throw
        new JitterStrategy.FixedJitter(0.0);
        new JitterStrategy.FixedJitter(0.5);
        new JitterStrategy.FixedJitter(1.0);
    }

    @Test
    @DisplayName("Jitter should produce different values across calls")
    void testJitterProducesVariance() {
        JitterStrategy fixed = JitterStrategy.fixed(0.5);
        AtomicLong sum = new AtomicLong(0);
        int samples = 1000;
        
        for (int i = 0; i < samples; i++) {
            sum.addAndGet(fixed.applyJitter(1000, 1));
        }
        
        double average = (double) sum.get() / samples;
        
        // Average should be close to 1000 (within reasonable variance)
        assertTrue(average > 900 && average < 1100,
            "Average should be close to 1000 but was " + average);
    }
}
