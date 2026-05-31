package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.junit.jupiter.api.Test;

class PolicyComposerTest {

    @Test
    void delegatesToInvocationAndReturnsItsValue() throws Exception {
        // MP FT 4.1 §2.5: with no active policy, the target method executes as-is.
        Object result = PolicyComposer.invoke(() -> "ok");

        assertEquals("ok", result);
    }

    @Test
    void propagatesInvocationException() {
        // MP FT 4.1 §2.5: outer layers will see the inner-layer exception.
        IOException error = assertThrows(
                IOException.class,
                () -> PolicyComposer.invoke(() -> {
                    throw new IOException("boom");
                })
        );

        assertEquals("boom", error.getMessage());
    }

    @Test
    void appliesFallbackWhenMethodDeclaresFallbackPolicy() throws Exception {
        // MP FT 4.1 §2.5: @Fallback is the outermost layer.
        FallbackService service = new FallbackService();
        Method guardedMethod = FallbackService.class.getDeclaredMethod("guarded");

        Object result = PolicyComposer.invoke(
                () -> {
                    throw new IOException("backend down");
                },
                service,
                guardedMethod,
                new Object[0]
        );

        assertEquals("fallback-value", result);
    }

    @Test
    void keepsFailFastBehaviorWhenNoFallbackIsDeclared() throws Exception {
        PlainService service = new PlainService();
        Method guardedMethod = PlainService.class.getDeclaredMethod("guarded");

        IOException error = assertThrows(
                IOException.class,
                () -> PolicyComposer.invoke(
                        () -> {
                            throw new IOException("still failing");
                        },
                        service,
                        guardedMethod,
                        new Object[0]
                )
        );

        assertEquals("still failing", error.getMessage());
    }

    @Test
    void retriesBeforeReturningSuccessfulResult() throws Exception {
        RetryOnlyService service = new RetryOnlyService();
        Method guardedMethod = RetryOnlyService.class.getDeclaredMethod("guarded");
        AtomicInteger calls = new AtomicInteger();

        Object result = PolicyComposer.invoke(
                () -> {
                    if (calls.getAndIncrement() < 2) {
                        throw new IOException("temporary");
                    }
                    return "eventual-success";
                },
                service,
                guardedMethod,
                new Object[0]
        );

        assertEquals("eventual-success", result);
        assertEquals(3, calls.get());
    }

    @Test
    void fallbackRunsAfterRetryExhaustion() throws Exception {
        RetryWithFallbackService service = new RetryWithFallbackService();
        Method guardedMethod = RetryWithFallbackService.class.getDeclaredMethod("guarded");
        AtomicInteger calls = new AtomicInteger();

        Object result = PolicyComposer.invoke(
                () -> {
                    calls.incrementAndGet();
                    throw new IOException("always failing");
                },
                service,
                guardedMethod,
                new Object[0]
        );

        assertEquals("recovered-after-retry", result);
        assertEquals(3, calls.get());
    }

    static class FallbackService {
        @Fallback(fallbackMethod = "recover")
        String guarded() {
            return "unreachable";
        }

        String recover() {
            return "fallback-value";
        }
    }

    static class PlainService {
        String guarded() {
            return "unreachable";
        }
    }

    static class RetryOnlyService {
        @Retry(maxRetries = 2)
        String guarded() {
            return "unreachable";
        }
    }

    static class RetryWithFallbackService {
        @Retry(maxRetries = 2, retryOn = IOException.class)
        @Fallback(fallbackMethod = "recover")
        String guarded() {
            return "unreachable";
        }

        String recover() {
            return "recovered-after-retry";
        }
    }
}

