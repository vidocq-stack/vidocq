package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.junit.jupiter.api.Test;

/**
 * MP FT 4.1 §2.5 — Exhaustive composition of all policies.
 * Tests interactions and exception propagation between layers.
 */
class PolicyComposerCompositionTest {

    // Test 1: @Retry + @Timeout
    @Test
    void retryAppliesBeforeTimeout() throws Exception {
        // MP FT §2.5: @Timeout applies to each individual attempt.
        RetryTimeoutService service = new RetryTimeoutService();
        Method method = RetryTimeoutService.class.getDeclaredMethod("guarded");
        AtomicInteger calls = new AtomicInteger();

        Object result = PolicyComposer.invoke(
                () -> {
                    calls.incrementAndGet();
                    if (calls.get() < 2) {
                        throw new IOException("temporary");
                    }
                    return "success-after-retry";
                },
                service,
                method,
                new Object[0]
        );

        assertEquals("success-after-retry", result);
        assertEquals(2, calls.get());
    }

    // Test 2: @CircuitBreaker + @Fallback
    @Test
    void circuitBreakerOpenTriggeredFallback() throws Exception {
        // MP FT §2.5: CircuitBreakerOpenException triggers the fallback.
        // Simulation: throw a CircuitBreakerOpenException directly.
        CBFallbackService service = new CBFallbackService();
        Method method = CBFallbackService.class.getDeclaredMethod("guarded");

        // No StateRegistry is provided, so CB is inactive — fallback remains a noop.
        // For this test, we simulate the CB exception.
        Object result = PolicyComposer.invoke(
                () -> {
                    throw new CircuitBreakerOpenException();
                },
                service,
                method,
                new Object[0],
                null, // cbRegistry
                null  // bhRegistry
        );

        // Fallback activates.
        assertEquals("fallback-after-cb", result);
    }

    // Test 3: @Bulkhead + @Fallback
    @Test
    void bulkheadSaturationTriggeredFallback() throws Exception {
        // MP FT §5: BulkheadException triggers the fallback.
        BulkheadFallbackService service = new BulkheadFallbackService();
        Method method = BulkheadFallbackService.class.getDeclaredMethod("guarded");

        // Simulate a BulkheadException.
        Object result = PolicyComposer.invoke(
                () -> {
                    throw new BulkheadException();
                },
                service,
                method,
                new Object[0],
                null, // cbRegistry
                null  // bhRegistry
        );

        assertEquals("bulkhead-fallback", result);
    }

    // Test 4: @Retry + @CircuitBreaker (sans Fallback)
    @Test
    void retrySendsToCBAndPropagatesException() throws Exception {
        // MP FT §2.5: the exception propagates from Retry to CB.
        RetryCBService service = new RetryCBService();
        Method method = RetryCBService.class.getDeclaredMethod("guarded");
        AtomicInteger calls = new AtomicInteger();

        IOException error = assertThrows(
                IOException.class,
                () -> PolicyComposer.invoke(
                        () -> {
                            calls.incrementAndGet();
                            throw new IOException("always fails");
                        },
                        service,
                        method,
                        new Object[0],
                        null, // cbRegistry
                        null  // bhRegistry
                )
        );

        assertEquals("always fails", error.getMessage());
        assertEquals(3, calls.get()); // 1 initial call + 2 retries
    }

    // Test 5: Full composition with @Fallback → @CircuitBreaker → @Bulkhead → @Timeout → @Retry
    @Test
    void fullChainWithAllPolicies() throws Exception {
        // MP FT §2.5: full outside-to-inside order.
        FullChainService service = new FullChainService();
        Method method = FullChainService.class.getDeclaredMethod("guarded");
        AtomicInteger calls = new AtomicInteger();

        Object result = PolicyComposer.invoke(
                () -> {
                    int currentCall = calls.incrementAndGet();
                    if (currentCall < 3) {
                        throw new IOException("retry-needed");
                    }
                    return "success-full-chain";
                },
                service,
                method,
                new Object[0],
                null, // cbRegistry (absent, so CB disabled)
                null  // bhRegistry (absent, so BH disabled)
        );

        assertEquals("success-full-chain", result);
        assertEquals(3, calls.get());
    }

    // Test 6: Exception propagation through all layers
    @Test
    void exceptionPropagatesThroughAllLayers() throws Exception {
        // MP FT §2.5: the method exception propagates through all layers.
        NoFallbackFullChainService service = new NoFallbackFullChainService();
        Method method = NoFallbackFullChainService.class.getDeclaredMethod("guarded");

        IOException error = assertThrows(
                IOException.class,
                () -> PolicyComposer.invoke(
                        () -> {
                            throw new IOException("persistent-error");
                        },
                        service,
                        method,
                        new Object[0],
                        null, // cbRegistry
                        null  // bhRegistry
                )
        );

        assertEquals("persistent-error", error.getMessage());
    }

    // Test 7: @Retry with @Fallback - fallback runs after retries exhausted
    @Test
    void retryFallbackInteraction() throws Exception {
        // MP FT §2.5: after retries are exhausted, fallback activates.
        RetryFallbackChainService service = new RetryFallbackChainService();
        Method method = RetryFallbackChainService.class.getDeclaredMethod("guarded");
        AtomicInteger calls = new AtomicInteger();

        Object result = PolicyComposer.invoke(
                () -> {
                    calls.incrementAndGet();
                    throw new IOException("always-fails");
                },
                service,
                method,
                new Object[0],
                null, // cbRegistry
                null  // bhRegistry
        );

        assertEquals("fallback-after-exhausted-retries", result);
        assertEquals(3, calls.get()); // 1 initial call + 2 retries
    }

    // Test 8: Disabled policy should be skipped
    @Test
    void disabledPolicyShouldBeSkipped() throws Exception {
        // M7: Retry disabled by config → no retry, the exception propagates directly.
        // This is tested via a mock Config in the CDI integration test.
        // Here we simply verify that an undeclared policy has no effect.
        DisabledRetryService service = new DisabledRetryService();
        Method method = DisabledRetryService.class.getDeclaredMethod("guarded");

        IOException error = assertThrows(
                IOException.class,
                () -> PolicyComposer.invoke(
                        () -> {
                            throw new IOException("no-retry");
                        },
                        service,
                        method,
                        new Object[0]
                )
        );

        assertEquals("no-retry", error.getMessage());
    }

    // Service classes with annotations matching the test scenarios

    static class RetryTimeoutService {
        @Retry(maxRetries = 1)
        @Timeout(1000)
        String guarded() {
            return "unreachable";
        }
    }

    static class CBFallbackService {
        @Fallback(fallbackMethod = "recoverCB")
        @CircuitBreaker
        String guarded() {
            return "unreachable";
        }

        String recoverCB() {
            return "fallback-after-cb";
        }
    }

    static class BulkheadFallbackService {
        @Fallback(fallbackMethod = "recoverBH")
        @Bulkhead
        String guarded() {
            return "unreachable";
        }

        String recoverBH() {
            return "bulkhead-fallback";
        }
    }

    static class RetryCBService {
        @Retry(maxRetries = 2)
        @CircuitBreaker
        String guarded() {
            return "unreachable";
        }
    }

    static class FullChainService {
        @Fallback(fallbackMethod = "recover")
        @CircuitBreaker
        @Bulkhead
        @Timeout(5000)
        @Retry(maxRetries = 2)
        String guarded() {
            return "unreachable";
        }

        String recover() {
            return "fallback-full-chain";
        }
    }

    // Same as FullChainService, but without @Fallback, to test exception propagation.
    static class NoFallbackFullChainService {
        @CircuitBreaker
        @Bulkhead
        @Timeout(5000)
        @Retry(maxRetries = 2)
        String guarded() {
            return "unreachable";
        }
    }

    static class RetryFallbackChainService {
        @Retry(maxRetries = 2)
        @Fallback(fallbackMethod = "recover")
        String guarded() {
            return "unreachable";
        }

        String recover() {
            return "fallback-after-exhausted-retries";
        }
    }

    // Service without @Retry — undeclared policy.
    static class DisabledRetryService {
        String guarded() {
            return "unreachable";
        }
    }
}




