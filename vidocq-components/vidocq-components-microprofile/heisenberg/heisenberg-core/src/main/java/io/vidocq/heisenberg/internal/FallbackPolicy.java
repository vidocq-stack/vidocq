package io.vidocq.heisenberg.internal;

import java.lang.reflect.Method;
import org.eclipse.microprofile.faulttolerance.Fallback;

public final class FallbackPolicy {

    private FallbackPolicy() {}

    public static Object execute(
            PolicyComposer.Invocation invocation,
            Fallback fallback,
            FallbackConfig config,
            Object target,
            Method guardedMethod,
            Object[] parameters,
            FallbackResolver resolver
    ) throws Exception {
        try {
            return invocation.proceed();
        } catch (Exception failure) {
            if (!config.shouldApplyFallback(failure)) {
                throw failure;
            }
            return resolver.resolve(fallback, target, guardedMethod, parameters, failure);
        }
    }
}

