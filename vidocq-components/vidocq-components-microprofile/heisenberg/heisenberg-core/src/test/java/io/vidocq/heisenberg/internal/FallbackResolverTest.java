package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import org.eclipse.microprofile.faulttolerance.ExecutionContext;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.FallbackHandler;
import org.eclipse.microprofile.faulttolerance.exceptions.FaultToleranceDefinitionException;
import org.junit.jupiter.api.Test;

class FallbackResolverTest {

    private final FallbackResolver resolver = new FallbackResolver();

    @Test
    void resolvesFallbackHandler() throws Exception {
        // MP FT 4.1 §6: fallback via FallbackHandler.handle(ExecutionContext).
        HandlerService service = new HandlerService();
        Method guardedMethod = HandlerService.class.getDeclaredMethod("guarded", String.class);
        Fallback fallback = guardedMethod.getAnnotation(Fallback.class);

        Object result = resolver.resolve(
                fallback,
                service,
                guardedMethod,
                new Object[]{"alpha"},
                new IOException("backend")
        );

        assertEquals("handler:alpha:backend", result);
    }

    @Test
    void resolvesFallbackMethodUsingMethodHandle() throws Exception {
        MethodService service = new MethodService();
        Method guardedMethod = MethodService.class.getDeclaredMethod("guarded", String.class);
        Fallback fallback = guardedMethod.getAnnotation(Fallback.class);

        Object result = resolver.resolve(
                fallback,
                service,
                guardedMethod,
                new Object[]{"beta"},
                new IOException("backend")
        );

        assertEquals("method:beta", result);
    }

    @Test
    void rejectsIncompatibleFallbackMethodReturnType() throws Exception {
        InvalidMethodService service = new InvalidMethodService();
        Method guardedMethod = InvalidMethodService.class.getDeclaredMethod("guarded");
        Fallback fallback = guardedMethod.getAnnotation(Fallback.class);

        FaultToleranceDefinitionException error = assertThrows(
                FaultToleranceDefinitionException.class,
                () -> resolver.validateDefinition(InvalidMethodService.class, guardedMethod, fallback)
        );

        assertTrue(error.getMessage().contains("Invalid fallbackMethod"));
    }

    @Test
    void rejectsMissingFallbackMethod() throws Exception {
        MissingMethodService service = new MissingMethodService();
        Method guardedMethod = MissingMethodService.class.getDeclaredMethod("guarded");
        Fallback fallback = guardedMethod.getAnnotation(Fallback.class);

        assertThrows(
                FaultToleranceDefinitionException.class,
                () -> resolver.resolve(fallback, service, guardedMethod, new Object[0], new IOException("fail"))
        );
    }

    static class HandlerService {
        @Fallback(Handler.class)
        String guarded(String value) {
            return "unreachable";
        }
    }

    public static class Handler implements FallbackHandler<String> {
        @Override
        public String handle(ExecutionContext context) {
            return "handler:"
                    + context.getParameters()[0]
                    + ":"
                    + context.getFailure().getMessage();
        }
    }

    static class MethodService {
        @Fallback(fallbackMethod = "recover")
        String guarded(String value) {
            return "unreachable";
        }

        private String recover(String value) {
            return "method:" + value;
        }
    }

    static class InvalidMethodService {
        @Fallback(fallbackMethod = "recover")
        String guarded() {
            return "unreachable";
        }

        int recover() {
            return 42;
        }
    }

    static class MissingMethodService {
        @Fallback(fallbackMethod = "recover")
        String guarded() {
            return "unreachable";
        }
    }
}

