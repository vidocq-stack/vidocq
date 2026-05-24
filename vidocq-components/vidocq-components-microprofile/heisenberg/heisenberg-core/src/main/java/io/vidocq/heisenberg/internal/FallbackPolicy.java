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
        return execute(invocation, fallback, config, target, guardedMethod, parameters, resolver, () -> {});
    }

    public static Object execute(
            PolicyComposer.Invocation invocation,
            Fallback fallback,
            FallbackConfig config,
            Object target,
            Method guardedMethod,
            Object[] parameters,
            FallbackResolver resolver,
            Runnable onFallbackApplied
    ) throws Exception {
        try {
            return invocation.proceed();
        } catch (Exception failure) {
            if (!config.shouldApplyFallback(failure)) {
                throw failure;
            }
            Object result;
            if (config.fallbackMethod() != null || config.fallbackHandlerClass() != null) {
                result = resolver.resolve(config, target, guardedMethod, parameters, failure);
            } else {
                result = resolver.resolve(fallback, target, guardedMethod, parameters, failure);
            }
            onFallbackApplied.run();
            return result;
        }
    }
}

