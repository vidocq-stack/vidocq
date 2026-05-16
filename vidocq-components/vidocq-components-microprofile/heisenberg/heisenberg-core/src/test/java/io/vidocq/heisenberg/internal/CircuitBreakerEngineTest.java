package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.junit.jupiter.api.Test;

/**
 * Tests CircuitBreakerEngine — M4 TDD.
 * MP FT 4.1 §5.
 */
class CircuitBreakerEngineTest {

    @Test
    void closedStateAllowsInvocations() throws Exception {
        var config = new CircuitBreakerConfig(20, 0.5, 5, ChronoUnit.SECONDS, 1,
                new Class[]{Exception.class}, new Class[0]);
        var registry = new TestRegistry();
        var engine = new CircuitBreakerEngine(config, registry);

        Object result = engine.execute(() -> "success", "TestClass", "testMethod");
        assertEquals("success", result);
    }

    @Test
    void openStateThrowsCircuitBreakerOpenException() throws Exception {
        var config = new CircuitBreakerConfig(20, 0.5, 5, ChronoUnit.SECONDS, 1,
                new Class[]{Exception.class}, new Class[0]);
        var registry = new TestRegistry();
        var engine = new CircuitBreakerEngine(config, registry);

        // Use setOpen() to properly initialize the timestamp
        registry.setOpen("TestClass", "testMethod");

        assertThrows(CircuitBreakerOpenException.class,
                () -> engine.execute(() -> "never", "TestClass", "testMethod"));
    }

    @Test
    void failureRecordingWhenInClosed() throws Exception {
        var config = new CircuitBreakerConfig(1, 0.5, 5, ChronoUnit.SECONDS, 1,
                new Class[]{RuntimeException.class}, new Class[0]);
        var registry = new TestRegistry();
        var engine = new CircuitBreakerEngine(config, registry);

        // First failure triggers OPEN (since requestVolumeThreshold=1, failureRatio=0.5)
        assertThrows(RuntimeException.class,
                () -> engine.execute(() -> { throw new RuntimeException("err"); }, "TestClass", "testMethod"));

        // Circuit should be OPEN now
        assertThrows(CircuitBreakerOpenException.class,
                () -> engine.execute(() -> "never", "TestClass", "testMethod"));
    }

    @Test
    void opensWhenFailureRatioReachedOnSlidingWindow() throws Exception {
        var config = new CircuitBreakerConfig(4, 0.5, 5, ChronoUnit.SECONDS, 1,
                new Class[]{RuntimeException.class}, new Class[0]);
        var registry = new TestRegistry();
        var engine = new CircuitBreakerEngine(config, registry);

        assertThrows(RuntimeException.class,
                () -> engine.execute(() -> {
                    throw new RuntimeException("f1");
                }, "TestClass", "thresholdMethod"));
        engine.execute(() -> "ok1", "TestClass", "thresholdMethod");
        assertThrows(RuntimeException.class,
                () -> engine.execute(() -> {
                    throw new RuntimeException("f2");
                }, "TestClass", "thresholdMethod"));
        engine.execute(() -> "ok2", "TestClass", "thresholdMethod");

        assertThrows(CircuitBreakerOpenException.class,
                () -> engine.execute(() -> "never", "TestClass", "thresholdMethod"));
    }

    // Minimal test registry
    static class TestRegistry implements CircuitBreakerStateRegistry {
        private CircuitBreakerState state = CircuitBreakerState.CLOSED;
        private long openedAtNanos = 0;
        private int halfOpenSuccesses = 0;
        private int failures = 0;

        @Override
        public CircuitBreakerState getState(String bc, String mn) { return state; }

        @Override
        public void recordSuccess(String bc, String mn) { halfOpenSuccesses++; }

        @Override
        public void recordFailure(String bc, String mn) {
            failures++;
        }

        @Override
        public void setOpen(String bc, String mn) {
            state = CircuitBreakerState.OPEN;
            openedAtNanos = System.nanoTime();
        }

        @Override
        public void setClosed(String bc, String mn) {
            state = CircuitBreakerState.CLOSED;
            failures = 0;
            halfOpenSuccesses = 0;
        }

        @Override
        public void setHalfOpen(String bc, String mn) {
            state = CircuitBreakerState.HALF_OPEN;
            halfOpenSuccesses = 0;
        }

        @Override
        public long getMillisSinceOpen(String bc, String mn) {
            return (System.nanoTime() - openedAtNanos) / 1_000_000;
        }

        @Override
        public int getSuccessesInHalfOpen(String bc, String mn) {
            return halfOpenSuccesses;
        }
    }
}



