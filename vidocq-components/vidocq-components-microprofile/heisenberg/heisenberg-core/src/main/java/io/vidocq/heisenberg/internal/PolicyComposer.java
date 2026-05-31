package io.vidocq.heisenberg.internal;

import io.vidocq.heisenberg.api.FtMetricsRecorder;
import java.lang.reflect.Method;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.faulttolerance.Asynchronous;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;

/**
 * Entry point of the Heisenberg engine — builds and executes the policy chain
 * in the order defined by the MicroProfile Fault Tolerance 4.1 spec §2.5:
 * {@code @Fallback → @CircuitBreaker → @Bulkhead → @Timeout → @Retry → method}.
 *
 * <p>Complete implemented chain (M1-M7): @Fallback → @CircuitBreaker → @Bulkhead → @Timeout → @Retry,
 * optionally wrapped by @Asynchronous (virtual thread).</p>
 */
public final class PolicyComposer {

    private static final FallbackResolver FALLBACK_RESOLVER = new FallbackResolver();

    private PolicyComposer() {}

    @FunctionalInterface
    public interface Invocation {
        Object proceed() throws Exception;
    }

    public static Object invoke(Invocation invocation) throws Exception {
        return invocation.proceed();
    }

    public static Object invoke(Invocation invocation, Object target, Method method, Object[] parameters) throws Exception {
        return invoke(invocation, target, method, parameters, null, null, FtMetricsRecorder.NOOP);
    }

    /**
     * Variant with optional registries for CircuitBreaker and Bulkhead (M4+).
     */
    public static Object invoke(Invocation invocation, Object target, Method method, Object[] parameters,
                                CircuitBreakerStateRegistry cbRegistry, BulkheadStateRegistry bhRegistry) throws Exception {
        return invoke(invocation, target, method, parameters, cbRegistry, bhRegistry, FtMetricsRecorder.NOOP);
    }

    /**
     * Complete variant with an MP Metrics recorder.
     */
    public static Object invoke(Invocation invocation, Object target, Method method, Object[] parameters,
                                CircuitBreakerStateRegistry cbRegistry, BulkheadStateRegistry bhRegistry,
                                FtMetricsRecorder recorder) throws Exception {
        if (method == null) {
            return invocation.proceed();
        }

        Class<?> runtimeBeanClass = resolveRuntimeBeanClass(target, method);
        AnnotationReader.FaultToleranceAnnotations annotations = AnnotationReader.read(method, runtimeBeanClass);

        // M7 — @Asynchronous
        Asynchronous asynchronous = annotations.asynchronous();
        boolean asyncActive = asynchronous != null && ConfigResolver.isAsynchronousEnabled(method, runtimeBeanClass);
        boolean completionStageSemantics = CompletionStage.class.isAssignableFrom(method.getReturnType());
        AtomicBoolean asyncInvocationStarted = asyncActive ? new AtomicBoolean(false) : null;

        // Unwrap the CompletionStage so the policies can see the exception (§8.2).
        final Invocation effectiveInvocation;
        if (asyncActive) {
            final Invocation raw = invocation;
            final AtomicBoolean startedFlag = asyncInvocationStarted;
            final Invocation markedRaw = () -> {
                startedFlag.set(true);
                return raw.proceed();
            };
            effectiveInvocation = completionStageSemantics
                    ? () -> unwrapAsyncResult(markedRaw.proceed())
                    : markedRaw;
        } else {
            effectiveInvocation = invocation;
        }

        String beanClassName = runtimeBeanClass.getName() + "@"
                + System.identityHashCode(runtimeBeanClass.getClassLoader());
        String methodKey = method.toGenericString();

        // ------------------------------------------------------------------
        // Read the annotations and register the metrics upfront.
        // ------------------------------------------------------------------
        Retry retry = annotations.retry();
        Timeout timeout = annotations.timeout();
        CircuitBreaker cb = annotations.circuitBreaker();
        Bulkhead bulkhead = annotations.bulkhead();
        Fallback fallback = annotations.fallback();

        boolean retryEnabled = retry != null && ConfigResolver.isRetryEnabled(method, runtimeBeanClass);
        boolean timeoutEnabled = timeout != null && ConfigResolver.isTimeoutEnabled(method, runtimeBeanClass);
        boolean cbEnabled = cb != null && ConfigResolver.isCircuitBreakerEnabled(method, runtimeBeanClass) && cbRegistry != null;
        boolean bulkheadEnabled = bulkhead != null && ConfigResolver.isBulkheadEnabled(method, runtimeBeanClass) && bhRegistry != null;
        boolean fallbackEnabled = fallback != null && ConfigResolver.isFallbackEnabled(method, runtimeBeanClass);

        final Fallback fallbackIfEnabled = fallbackEnabled ? fallback : null;
        final FallbackConfig fallbackConfig = fallbackIfEnabled == null ? null
                : ConfigResolver.fallbackConfig(method, runtimeBeanClass, fallbackIfEnabled);

        recorder.register(runtimeBeanClass, method,
                retryEnabled, timeoutEnabled, cbEnabled, bulkheadEnabled, fallbackEnabled,
                asyncActive && bulkheadEnabled);

        // ------------------------------------------------------------------
        // Build the chain from the inside out.
        // Outer→inner : Fallback → Retry → CB → Timeout → Bulkhead → method.
        // ------------------------------------------------------------------

        // Layer 1 (innermost): @Bulkhead
        Invocation base = effectiveInvocation;
        if (bulkheadEnabled) {
            BulkheadConfig bhConfig = ConfigResolver.bulkheadConfig(method, runtimeBeanClass, bulkhead);
            final Invocation rawBase = effectiveInvocation;
            final boolean asyncMode = asyncActive;
            base = () -> {
                BulkheadEngine engine = new BulkheadEngine(bhConfig, bhRegistry);
                if (asyncMode) {
                    return engine.executeAsync(rawBase, beanClassName, methodKey, recorder, runtimeBeanClass, method);
                }
                return engine.execute(rawBase, beanClassName, methodKey, recorder, runtimeBeanClass, method);
            };
        }

        // Layer 2: @Timeout — wraps Bulkhead to account for queue time
        Invocation withTimeout = base;
        if (timeoutEnabled) {
            TimeoutConfig timeoutConfig = ConfigResolver.timeoutConfig(method, runtimeBeanClass, timeout);
            final Invocation bulkheadBase = base;
            final boolean asyncCall = asyncActive;
            withTimeout = () -> {
                long start = System.nanoTime();
                boolean timedOut = false;
                try {
                    return TimeoutEngine.execute(bulkheadBase, timeoutConfig, asyncCall);
                } catch (TimeoutException e) {
                    timedOut = true;
                    throw e;
                } finally {
                    recorder.recordTimeout(runtimeBeanClass, method, timedOut, System.nanoTime() - start);
                }
            };
        }

        // Layer 3: @CircuitBreaker — sees each attempt individually
        Invocation withCB = withTimeout;
        if (cbEnabled) {
            CircuitBreakerConfig cbConfig = ConfigResolver.circuitBreakerConfig(method, runtimeBeanClass, cb);
            final Invocation timeoutBase = withTimeout;
            withCB = () -> {
                CircuitBreakerState stateBefore = cbRegistry.getState(beanClassName, methodKey);
                FtMetricsRecorder.CBCallResult[] cbResult = {FtMetricsRecorder.CBCallResult.FAILURE};
                try {
                    Object r = new CircuitBreakerEngine(cbConfig, cbRegistry)
                            .execute(timeoutBase, beanClassName, methodKey);
                    cbResult[0] = FtMetricsRecorder.CBCallResult.SUCCESS;
                    return r;
                } catch (CircuitBreakerOpenException e) {
                    cbResult[0] = FtMetricsRecorder.CBCallResult.CIRCUIT_BREAKER_OPEN;
                    throw e;
                } catch (Throwable t) {
                    // MP FT 4.1 §5.1.1: an exception not listed in failOn (or listed in
                    // skipOn) does NOT count as a CB failure — it must be reported as
                    // SUCCESS in the ft.circuitbreaker.calls.total metric.
                    cbResult[0] = isCircuitBreakerFailure(t, cbConfig)
                            ? FtMetricsRecorder.CBCallResult.FAILURE
                            : FtMetricsRecorder.CBCallResult.SUCCESS;
                    throw t;
                } finally {
                    recorder.recordCircuitBreakerCall(runtimeBeanClass, method, cbResult[0]);
                    CircuitBreakerState stateAfter = cbRegistry.getState(beanClassName, methodKey);
                    if (stateAfter != stateBefore) {
                        recorder.notifyCircuitBreakerStateChange(runtimeBeanClass, method,
                                toCBState(stateBefore), toCBState(stateAfter));
                    }
                }
            };
        }

        // Layer 4: @Retry — outermost so it can replay CB/Bulkhead/Timeout
        Invocation withRetry = withCB;
        if (retryEnabled) {
            RetryConfig retryConfig = ConfigResolver.retryConfig(method, runtimeBeanClass, retry);
            final Invocation withCBBase = withCB;
            withRetry = () -> RetryEngine.execute(withCBBase, retryConfig, recorder, runtimeBeanClass, method);
        }
        final Invocation withRetryFinal = withRetry;

        // Layer 5: @Fallback + @Asynchronous
        if (asyncActive) {
            final Fallback fallbackFinal = fallbackIfEnabled;
            final FallbackConfig fallbackConfigFinal = fallbackConfig;
            final Object targetFinal = target;
            final Method methodFinal = method;
            final Object[] parametersFinal = parameters;
            final Class<?> beanClassFinal = runtimeBeanClass;
            final Invocation withFallback;
            if (fallbackFinal == null) {
                final Invocation plain = withRetryFinal;
                withFallback = () -> {
                    boolean ok = false;
                    try {
                        Object r = completionStageSemantics
                                ? unwrapAsyncResult(plain.proceed())
                                : plain.proceed();
                        ok = true;
                        return r;
                    } finally {
                        recorder.recordInvocation(beanClassFinal, methodFinal, ok, false, false);
                    }
                };
            } else {
                withFallback = () -> {
                    AtomicBoolean applied = new AtomicBoolean(false);
                    boolean ok = false;
                    try {
                        Object r = completionStageSemantics
                                ? unwrapAsyncResult(FallbackPolicy.execute(withRetryFinal, fallbackFinal,
                                        fallbackConfigFinal, targetFinal, methodFinal, parametersFinal,
                                        FALLBACK_RESOLVER, () -> applied.set(true)))
                                : FallbackPolicy.execute(withRetryFinal, fallbackFinal, fallbackConfigFinal,
                                        targetFinal, methodFinal, parametersFinal, FALLBACK_RESOLVER,
                                        () -> applied.set(true));
                        ok = true;
                        return r;
                    } finally {
                        recorder.recordInvocation(beanClassFinal, methodFinal, ok, applied.get(), true);
                    }
                };
            }
            return AsynchronousEngine.executeAsync(withFallback, method.getName(), asyncInvocationStarted);
        }

        // Synchronous path
        AtomicBoolean fallbackApplied = new AtomicBoolean(false);
        boolean succeeded = false;
        try {
            Object result;
            if (fallbackIfEnabled == null) {
                result = withRetry.proceed();
            } else {
                result = FallbackPolicy.execute(withRetry, fallbackIfEnabled, fallbackConfig, target, method,
                        parameters, FALLBACK_RESOLVER, () -> fallbackApplied.set(true));
            }
            succeeded = true;
            return result;
        } finally {
            recorder.recordInvocation(runtimeBeanClass, method, succeeded, fallbackApplied.get(), fallbackIfEnabled != null);
        }
    }

    private static FtMetricsRecorder.CBState toCBState(CircuitBreakerState state) {
        return switch (state) {
            case CLOSED -> FtMetricsRecorder.CBState.CLOSED;
            case OPEN -> FtMetricsRecorder.CBState.OPEN;
            case HALF_OPEN -> FtMetricsRecorder.CBState.HALF_OPEN;
        };
    }

    /**
     * MP FT 4.1 §5.1.1: determines whether an exception thrown by the protected method
     * must be counted as a circuit-breaker failure.
     *
     * <p>{@code skipOn} takes precedence over {@code failOn}; an exception not listed in
     * {@code failOn} (or listed in {@code skipOn}) does NOT count as a failure and must
     * be reported as success in the {@code ft.circuitbreaker.calls.total} metric
     * (result {@code success}).</p>
     */
    private static boolean isCircuitBreakerFailure(Throwable failure, CircuitBreakerConfig config) {
        if (matchesAny(failure, config.skipOn())) return false;
        return matchesAny(failure, config.failOn());
    }

    private static boolean matchesAny(Throwable failure, Class<? extends Throwable>[] types) {
        if (types == null) return false;
        for (Class<? extends Throwable> type : types) {
            if (type != null && type.isAssignableFrom(failure.getClass())) return true;
        }
        return false;
    }

    private static Class<?> resolveRuntimeBeanClass(Object target, Method method) {
        if (target == null) {
            return method.getDeclaringClass();
        }

        Class<?> current = target.getClass();
        while (current.getName().contains("$$Intercepted") && current.getSuperclass() != null) {
            current = current.getSuperclass();
        }
        return current;
    }

    /**
     * Unwraps the potentially asynchronous result of an invocation: if the result is a
     * {@link CompletionStage} or a {@link java.util.concurrent.Future}, waits for its completion
     * and propagates the causal exception as-is so the policies can see it (§8.2).
     */
    private static Object unwrapAsyncResult(Object result) throws Exception {
        if (result instanceof CompletionStage<?> stage) {
            try {
                return stage.toCompletableFuture().get();
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof Exception ex) throw ex;
                if (cause instanceof Error err) throw err;
                throw new RuntimeException(cause);
            }
        }
        return result;
    }
}
