package io.vidocq.heisenberg.cdi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.temporal.ChronoUnit;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.junit.jupiter.api.Test;

class TimeoutIntegrationTest {

    private final FaultToleranceInterceptor interceptor = new FaultToleranceInterceptor();

    @Test
    void timeoutAnnotationTriggersTimeoutExceptionOnSlowMethod() throws Exception {
        // §4 : une méthode qui dépasse la valeur @Timeout doit lever TimeoutException.
        TimeoutService target = new TimeoutService();
        Method method = TimeoutService.class.getDeclaredMethod("slow");
        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[0]);

        assertThrows(TimeoutException.class, () -> interceptor.around(context));
    }

    @Test
    void fastMethodCompletesWithinTimeoutWithoutException() throws Exception {
        // §4 : une méthode qui se termine avant la deadline retourne normalement.
        FastService target = new FastService();
        Method method = FastService.class.getDeclaredMethod("fast");
        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[0]);

        Object result = interceptor.around(context);

        assertEquals("done", result);
    }

    @Test
    void timeoutAppliesPerRetryAttempt() throws Exception {
        // §4.1 : le timeout s'applique à chaque tentative individuelle.
        // Chaque tentative dépasse le timeout → maxRetries épuisés → TimeoutException.
        TimeoutRetryService target = new TimeoutRetryService();
        Method method = TimeoutRetryService.class.getDeclaredMethod("call");
        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[0]);

        assertThrows(TimeoutException.class, () -> interceptor.around(context));
        // 1 tentative originale + 2 retries = 3 appels au total
        assertEquals(3, target.attempts);
    }

    @Test
    void fallbackActivatesAfterTimeoutWhenFallbackIsDeclared() throws Exception {
        // §4 + §6 : TimeoutException déclenche le fallback s'il est déclaré.
        TimeoutFallbackService target = new TimeoutFallbackService();
        Method method = TimeoutFallbackService.class.getDeclaredMethod("slow");
        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[0]);

        Object result = interceptor.around(context);

        assertEquals("fallback-on-timeout", result);
    }

    // ---- Services de test ----

    static class TimeoutService {
        @Timeout(value = 100, unit = ChronoUnit.MILLIS)
        String slow() throws Exception {
            Thread.sleep(500);
            return "too-late";
        }
    }

    static class FastService {
        @Timeout(value = 500, unit = ChronoUnit.MILLIS)
        String fast() {
            return "done";
        }
    }

    static class TimeoutRetryService {
        int attempts;

        @Timeout(value = 100, unit = ChronoUnit.MILLIS)
        @Retry(maxRetries = 2, retryOn = TimeoutException.class)
        String call() throws Exception {
            attempts++;
            Thread.sleep(500);
            return "too-late";
        }
    }

    static class TimeoutFallbackService {
        @Timeout(value = 100, unit = ChronoUnit.MILLIS)
        @Fallback(fallbackMethod = "recover")
        String slow() throws Exception {
            Thread.sleep(500);
            return "too-late";
        }

        String recover() {
            return "fallback-on-timeout";
        }
    }
}

