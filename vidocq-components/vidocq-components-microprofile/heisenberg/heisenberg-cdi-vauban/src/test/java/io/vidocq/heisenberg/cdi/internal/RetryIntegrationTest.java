package io.vidocq.heisenberg.cdi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.lang.reflect.Method;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.junit.jupiter.api.Test;

class RetryIntegrationTest {

    private final FaultToleranceInterceptor interceptor = new FaultToleranceInterceptor();

    @Test
    void retriesUntilSuccessWhenRetryOnMatches() throws Exception {
        RetryService target = new RetryService();
        Method method = RetryService.class.getDeclaredMethod("call");
        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[0]);

        Object result = interceptor.around(context);

        assertEquals("ok", result);
        assertEquals(3, target.calls);
    }

    @Test
    void triggersFallbackAfterRetryExhaustion() throws Exception {
        RetryFallbackService target = new RetryFallbackService();
        Method method = RetryFallbackService.class.getDeclaredMethod("call");
        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[0]);

        Object result = interceptor.around(context);

        assertEquals("fallback", result);
        assertEquals(3, target.calls);
    }

    static class RetryService {
        private int calls;

        @Retry(maxRetries = 2, retryOn = IOException.class)
        String call() throws IOException {
            calls++;
            if (calls < 3) {
                throw new IOException("temporary");
            }
            return "ok";
        }
    }

    static class RetryFallbackService {
        private int calls;

        @Retry(maxRetries = 2, retryOn = IOException.class)
        @Fallback(fallbackMethod = "recover")
        String call() throws IOException {
            calls++;
            throw new IOException("still failing");
        }

        String recover() {
            return "fallback";
        }
    }
}
