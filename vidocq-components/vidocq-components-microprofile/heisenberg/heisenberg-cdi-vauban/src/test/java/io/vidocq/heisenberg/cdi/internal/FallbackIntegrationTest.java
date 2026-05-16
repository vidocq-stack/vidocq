package io.vidocq.heisenberg.cdi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.faulttolerance.ExecutionContext;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.FallbackHandler;
import org.junit.jupiter.api.Test;

class FallbackIntegrationTest {

    private final FaultToleranceInterceptor interceptor = new FaultToleranceInterceptor();

    @Test
    void appliesFallbackMethodThroughInterceptor() throws Exception {
        MethodFallbackService target = new MethodFallbackService();
        Method method = MethodFallbackService.class.getDeclaredMethod("call", String.class);
        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[]{"alpha"});

        Object result = interceptor.around(context);

        assertEquals("fallback-method:alpha", result);
    }

    @Test
    void appliesFallbackHandlerThroughInterceptor() throws Exception {
        HandlerFallbackService target = new HandlerFallbackService();
        Method method = HandlerFallbackService.class.getDeclaredMethod("call", String.class);
        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[]{"beta"});

        Object result = interceptor.around(context);

        assertEquals("fallback-handler:beta", result);
    }

    static class MethodFallbackService {
        @Fallback(fallbackMethod = "recover")
        String call(String value) {
            throw new IllegalStateException("boom");
        }

        String recover(String value) {
            return "fallback-method:" + value;
        }
    }

    static class HandlerFallbackService {
        @Fallback(Handler.class)
        String call(String value) {
            throw new IllegalStateException("boom");
        }
    }

    public static class Handler implements FallbackHandler<String> {
        @Override
        public String handle(ExecutionContext context) {
            return "fallback-handler:" + context.getParameters()[0];
        }
    }
}
