package com.pi.agent.llm;

import java.security.SecureRandom;
import java.util.Random;

/**
 * Jitter strategies for retry backoff.
 * Helps prevent thundering herd problems when multiple clients retry simultaneously.
 */
public interface JitterStrategy {

    /**
     * Apply jitter to a backoff duration.
     * 
     * @param baseBackoffMs The calculated base backoff in milliseconds
     * @param attempt The current retry attempt number
     * @return The jittered backoff in milliseconds
     */
    long applyJitter(long baseBackoffMs, int attempt);

    /**
     * No jitter - returns the backoff unchanged.
     */
    JitterStrategy NONE = (baseBackoffMs, attempt) -> baseBackoffMs;

    /**
     * Fixed jitter - adds a fixed random delay within a range.
     * Range: [baseBackoffMs * (1 - factor), baseBackoffMs * (1 + factor)]
     */
    class FixedJitter implements JitterStrategy {
        private final double factor;
        private final Random random;

        public FixedJitter(double factor) {
            this(factor, new SecureRandom());
        }

        public FixedJitter(double factor, Random random) {
            if (factor < 0 || factor > 1) {
                throw new IllegalArgumentException("Factor must be between 0 and 1");
            }
            this.factor = factor;
            this.random = random;
        }

        @Override
        public long applyJitter(long baseBackoffMs, int attempt) {
            double jitterRange = baseBackoffMs * factor;
            double jitter = (random.nextDouble() * 2 - 1) * jitterRange;
            return Math.max(0, (long) (baseBackoffMs + jitter));
        }
    }

    /**
     * Equal jitter - adds random delay equal to half the backoff.
     * Ensures minimum wait time while still randomizing.
     * Formula: baseBackoffMs/2 + random(0, baseBackoffMs/2)
     */
    class EqualJitter implements JitterStrategy {
        private final Random random;

        public EqualJitter() {
            this(new SecureRandom());
        }

        public EqualJitter(Random random) {
            this.random = random;
        }

        @Override
        public long applyJitter(long baseBackoffMs, int attempt) {
            long halfBackoff = baseBackoffMs / 2;
            return halfBackoff + (long) (random.nextDouble() * halfBackoff);
        }
    }

    /**
     * Full jitter - random delay from 0 to baseBackoffMs.
     * Best for distributed systems with many clients.
     * Formula: random(0, baseBackoffMs)
     */
    class FullJitter implements JitterStrategy {
        private final Random random;

        public FullJitter() {
            this(new SecureRandom());
        }

        public FullJitter(Random random) {
            this.random = random;
        }

        @Override
        public long applyJitter(long baseBackoffMs, int attempt) {
            return (long) (random.nextDouble() * baseBackoffMs);
        }
    }

    /**
     * Decorrelated jitter - adds random delay that decorrelates from previous attempts.
     * Best for preventing synchronization across retries.
     * Formula: random(initialBackoffMs, min(maxBackoffMs, baseBackoffMs * 3))
     */
    class DecorrelatedJitter implements JitterStrategy {
        private final long initialBackoffMs;
        private final long maxBackoffMs;
        private final Random random;
        private long lastBackoffMs;

        public DecorrelatedJitter(long initialBackoffMs, long maxBackoffMs) {
            this(initialBackoffMs, maxBackoffMs, new SecureRandom());
        }

        public DecorrelatedJitter(long initialBackoffMs, long maxBackoffMs, Random random) {
            this.initialBackoffMs = initialBackoffMs;
            this.maxBackoffMs = maxBackoffMs;
            this.random = random;
            this.lastBackoffMs = initialBackoffMs;
        }

        @Override
        public long applyJitter(long baseBackoffMs, int attempt) {
            long upperBound = Math.min(maxBackoffMs, lastBackoffMs * 3);
            long jittered = initialBackoffMs + (long) (random.nextDouble() * (upperBound - initialBackoffMs));
            lastBackoffMs = jittered;
            return jittered;
        }

        /**
         * Reset the decorrelation state.
         */
        public void reset() {
            lastBackoffMs = initialBackoffMs;
        }
    }

    /**
     * Create a fixed jitter strategy with the given factor (0.0 to 1.0).
     * A factor of 0.2 means ±20% variation.
     */
    static JitterStrategy fixed(double factor) {
        return new FixedJitter(factor);
    }

    /**
     * Create an equal jitter strategy.
     */
    static JitterStrategy equal() {
        return new EqualJitter();
    }

    /**
     * Create a full jitter strategy.
     */
    static JitterStrategy full() {
        return new FullJitter();
    }

    /**
     * Create a decorrelated jitter strategy.
     */
    static JitterStrategy decorrelated(long initialBackoffMs, long maxBackoffMs) {
        return new DecorrelatedJitter(initialBackoffMs, maxBackoffMs);
    }
}
