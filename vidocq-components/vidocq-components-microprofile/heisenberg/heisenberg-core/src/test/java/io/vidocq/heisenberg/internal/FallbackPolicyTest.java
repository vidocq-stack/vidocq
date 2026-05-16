package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.junit.jupiter.api.Test;

class FallbackPolicyTest {

    private final FallbackResolver resolver = new FallbackResolver();

    @Test
    void appliesFallbackWhenExceptionMatchesApplyOn() throws Exception {
        // MP FT 4.1 §6: fallback déclenché si exception dans applyOn et pas dans skipOn.
        PolicyService service = new PolicyService();
        Method guardedMethod = PolicyService.class.getDeclaredMethod("guarded");
        Fallback fallback = guardedMethod.getAnnotation(Fallback.class);

        Object result = FallbackPolicy.execute(
                () -> {
                    throw new IllegalStateException("boom");
                },
                fallback,
                new FallbackConfig(fallback.applyOn(), fallback.skipOn()),
                service,
                guardedMethod,
                new Object[0],
                resolver
        );

        assertEquals("recovered", result);
    }

    @Test
    void doesNotApplyFallbackWhenExceptionMatchesSkipOn() throws Exception {
        PolicyService service = new PolicyService();
        Method guardedMethod = PolicyService.class.getDeclaredMethod("guarded");
        Fallback fallback = guardedMethod.getAnnotation(Fallback.class);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> FallbackPolicy.execute(
                        () -> {
                            throw new IllegalArgumentException("do not fallback");
                        },
                        fallback,
                        new FallbackConfig(fallback.applyOn(), fallback.skipOn()),
                        service,
                        guardedMethod,
                        new Object[0],
                        resolver
                )
        );

        assertEquals("do not fallback", error.getMessage());
    }

    static class PolicyService {
        @Fallback(
                fallbackMethod = "recover",
                applyOn = {IllegalStateException.class, RuntimeException.class},
                skipOn = {IllegalArgumentException.class}
        )
        String guarded() {
            return "unreachable";
        }

        String recover() {
            return "recovered";
        }
    }
}
