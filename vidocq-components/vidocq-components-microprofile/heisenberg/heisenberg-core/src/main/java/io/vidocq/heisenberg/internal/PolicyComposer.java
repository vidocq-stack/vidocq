/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
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

    /**
     * Wraps the work an {@code @Asynchronous} method runs on its own thread, the whole policy chain
     * included, so that the CDI layer can give it the contexts MicroProfile Fault Tolerance requires
     * there: the request context must be active (§5, {@code AsynchronousTest#testAsyncRequestContext*}).
     * This module knows nothing of CDI; {@link #NONE} leaves the work as it is.
     */
    @FunctionalInterface
    public interface AsyncContext {
        AsyncContext NONE = invocation -> invocation;

        Invocation wrap(Invocation invocation);
    }

    public static Object invoke(Invocation invocation) throws Exception {
        return invocation.proceed();
    }

    /**
     * What the policies need from the container: the circuit-breaker and bulkhead state registries
     * (either may be {@code null}, which leaves that policy out), the metrics recorder, and the
     * wrapper an {@code @Asynchronous} method's work runs in.
     */
    public record Services(CircuitBreakerStateRegistry cbRegistry, BulkheadStateRegistry bhRegistry,
                           FtMetricsRecorder recorder, AsyncContext asyncContext) {}

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
        return invoke(invocation, target, method, parameters,
                new Services(cbRegistry, bhRegistry, recorder, AsyncContext.NONE));
    }

    /** As above, with {@code services.asyncContext()} wrapping the work of an {@code @Asynchronous} method. */
    public static Object invoke(Invocation invocation, Object target, Method method, Object[] parameters,
                                Services services) throws Exception {
        if (method == null) {
            return invocation.proceed();
        }
        Class<?> runtimeBeanClass = resolveRuntimeBeanClass(target, method);
        return new Chain(method, runtimeBeanClass, services).invoke(invocation, target, parameters);
    }

    /**
     * The policies of one guarded method, each one {@code null} when the method does not carry it or
     * MicroProfile Config disables it.
     */
    private record EnabledPolicies(Retry retry, Timeout timeout, CircuitBreaker circuitBreaker,
                                   Bulkhead bulkhead, Fallback fallback) {

        static EnabledPolicies of(AnnotationReader.FaultToleranceAnnotations annotations, Method method,
                                  Class<?> beanClass, Services services) {
            Retry retry = annotations.retry();
            Timeout timeout = annotations.timeout();
            CircuitBreaker cb = annotations.circuitBreaker();
            Bulkhead bulkhead = annotations.bulkhead();
            Fallback fallback = annotations.fallback();

            boolean retryEnabled = retry != null && ConfigResolver.isRetryEnabled(method, beanClass);
            boolean timeoutEnabled = timeout != null && ConfigResolver.isTimeoutEnabled(method, beanClass);
            boolean cbEnabled = cb != null && ConfigResolver.isCircuitBreakerEnabled(method, beanClass)
                    && services.cbRegistry() != null;
            boolean bulkheadEnabled = bulkhead != null && ConfigResolver.isBulkheadEnabled(method, beanClass)
                    && services.bhRegistry() != null;
            boolean fallbackEnabled = fallback != null && ConfigResolver.isFallbackEnabled(method, beanClass);
            return new EnabledPolicies(retryEnabled ? retry : null, timeoutEnabled ? timeout : null,
                    cbEnabled ? cb : null, bulkheadEnabled ? bulkhead : null, fallbackEnabled ? fallback : null);
        }
    }

    /**
     * Builds and runs the policy chain of one invocation of a guarded method.
     *
     * <p>The chain is built from the inside out.
     * Outer→inner : Fallback → Retry → CB → Timeout → Bulkhead → method.</p>
     */
    private static final class Chain {
        private final Method method;
        private final Class<?> beanClass;
        private final Services services;
        private final FtMetricsRecorder recorder;
        private final String beanClassName;
        private final String methodKey;

        Chain(Method method, Class<?> beanClass, Services services) {
            this.method = method;
            this.beanClass = beanClass;
            this.services = services;
            this.recorder = services.recorder();
            this.beanClassName = beanClass.getName() + "@" + System.identityHashCode(beanClass.getClassLoader());
            this.methodKey = method.toGenericString();
        }

        Object invoke(Invocation invocation, Object target, Object[] parameters) throws Exception {
            AnnotationReader.FaultToleranceAnnotations annotations = AnnotationReader.read(method, beanClass);

            // M7 — @Asynchronous
            boolean asyncActive = annotations.asynchronous() != null
                    && ConfigResolver.isAsynchronousEnabled(method, beanClass);
            boolean completionStageSemantics = CompletionStage.class.isAssignableFrom(method.getReturnType());
            AtomicBoolean asyncInvocationStarted = asyncActive ? new AtomicBoolean(false) : null;
            Invocation effectiveInvocation = asyncActive
                    ? markStarted(invocation, asyncInvocationStarted, completionStageSemantics)
                    : invocation;

            // Read the annotations and register the metrics upfront.
            EnabledPolicies policies = EnabledPolicies.of(annotations, method, beanClass, services);
            FallbackPolicy fallbackPolicy = policies.fallback() == null ? null : new FallbackPolicy(policies.fallback(),
                    ConfigResolver.fallbackConfig(method, beanClass, policies.fallback()), FALLBACK_RESOLVER);
            recorder.register(beanClass, method,
                    policies.retry() != null, policies.timeout() != null, policies.circuitBreaker() != null,
                    policies.bulkhead() != null, fallbackPolicy != null,
                    asyncActive && policies.bulkhead() != null);

            Invocation chain = build(effectiveInvocation, policies, asyncActive);

            // Layer 5: @Fallback + @Asynchronous
            if (asyncActive) {
                Invocation withFallback = fallbackPolicy == null
                        ? recorded(chain, completionStageSemantics)
                        : recordedWithFallback(chain, fallbackPolicy, target, parameters, completionStageSemantics);
                return AsynchronousEngine.executeAsync(services.asyncContext().wrap(withFallback), method.getName(),
                        asyncInvocationStarted);
            }
            return runSync(chain, fallbackPolicy, target, parameters);
        }

        /** Marks the invocation as started, and unwraps its stage so the policies can see the exception (§8.2). */
        private static Invocation markStarted(Invocation raw, AtomicBoolean startedFlag,
                                              boolean completionStageSemantics) {
            Invocation markedRaw = () -> {
                startedFlag.set(true);
                return raw.proceed();
            };
            return completionStageSemantics ? () -> unwrapAsyncResult(markedRaw.proceed()) : markedRaw;
        }

        private Invocation build(Invocation invocation, EnabledPolicies policies, boolean asyncActive) {
            Invocation chain = invocation;
            if (policies.bulkhead() != null) {
                chain = withBulkhead(chain, ConfigResolver.bulkheadConfig(method, beanClass, policies.bulkhead()),
                        asyncActive);
            }
            if (policies.timeout() != null) {
                chain = withTimeout(chain, ConfigResolver.timeoutConfig(method, beanClass, policies.timeout()),
                        asyncActive);
            }
            if (policies.circuitBreaker() != null) {
                chain = withCircuitBreaker(chain,
                        ConfigResolver.circuitBreakerConfig(method, beanClass, policies.circuitBreaker()));
            }
            if (policies.retry() != null) {
                RetryConfig retryConfig = ConfigResolver.retryConfig(method, beanClass, policies.retry());
                Invocation withCBBase = chain;
                chain = () -> RetryEngine.execute(withCBBase, retryConfig, recorder, beanClass, method);
            }
            return chain;
        }

        /** Layer 1 (innermost): @Bulkhead. */
        private Invocation withBulkhead(Invocation rawBase, BulkheadConfig bhConfig, boolean asyncMode) {
            BulkheadStateRegistry bhRegistry = services.bhRegistry();
            return () -> {
                BulkheadEngine engine = new BulkheadEngine(bhConfig, bhRegistry);
                if (asyncMode) {
                    return engine.executeAsync(rawBase, beanClassName, methodKey, recorder, beanClass, method);
                }
                return engine.execute(rawBase, beanClassName, methodKey, recorder, beanClass, method);
            };
        }

        /** Layer 2: @Timeout — wraps Bulkhead to account for queue time. */
        private Invocation withTimeout(Invocation bulkheadBase, TimeoutConfig timeoutConfig, boolean asyncCall) {
            return () -> {
                long start = System.nanoTime();
                boolean timedOut = false;
                try {
                    return TimeoutEngine.execute(bulkheadBase, timeoutConfig, asyncCall);
                } catch (TimeoutException e) {
                    timedOut = true;
                    throw e;
                } finally {
                    recorder.recordTimeout(beanClass, method, timedOut, System.nanoTime() - start);
                }
            };
        }

        /** Layer 3: @CircuitBreaker — sees each attempt individually. */
        private Invocation withCircuitBreaker(Invocation timeoutBase, CircuitBreakerConfig cbConfig) {
            CircuitBreakerStateRegistry cbRegistry = services.cbRegistry();
            return () -> {
                CircuitBreakerState stateBefore = cbRegistry.getState(beanClassName, methodKey);
                FtMetricsRecorder.CBCallResult cbResult = FtMetricsRecorder.CBCallResult.FAILURE;
                try {
                    Object r = new CircuitBreakerEngine(cbConfig, cbRegistry)
                            .execute(timeoutBase, beanClassName, methodKey);
                    cbResult = FtMetricsRecorder.CBCallResult.SUCCESS;
                    return r;
                } catch (CircuitBreakerOpenException e) {
                    cbResult = FtMetricsRecorder.CBCallResult.CIRCUIT_BREAKER_OPEN;
                    throw e;
                } catch (Throwable t) {
                    // MP FT 4.1 §5.1.1: an exception not listed in failOn (or listed in
                    // skipOn) does NOT count as a CB failure — it must be reported as
                    // SUCCESS in the ft.circuitbreaker.calls.total metric.
                    cbResult = isCircuitBreakerFailure(t, cbConfig)
                            ? FtMetricsRecorder.CBCallResult.FAILURE
                            : FtMetricsRecorder.CBCallResult.SUCCESS;
                    throw t;
                } finally {
                    recorder.recordCircuitBreakerCall(beanClass, method, cbResult);
                    CircuitBreakerState stateAfter = cbRegistry.getState(beanClassName, methodKey);
                    if (stateAfter != stateBefore) {
                        recorder.notifyCircuitBreakerStateChange(beanClass, method,
                                toCBState(stateBefore), toCBState(stateAfter));
                    }
                }
            };
        }

        /** The asynchronous work without a fallback: records the invocation outcome. */
        private Invocation recorded(Invocation plain, boolean completionStageSemantics) {
            return () -> {
                boolean ok = false;
                try {
                    Object r = completionStageSemantics
                            ? unwrapAsyncResult(plain.proceed())
                            : plain.proceed();
                    ok = true;
                    return r;
                } finally {
                    recorder.recordInvocation(beanClass, method, ok, false, false);
                }
            };
        }

        /** The asynchronous work with a fallback: records the invocation outcome and whether it fell back. */
        private Invocation recordedWithFallback(Invocation withRetry, FallbackPolicy fallbackPolicy, Object target,
                                                Object[] parameters, boolean completionStageSemantics) {
            return () -> {
                AtomicBoolean applied = new AtomicBoolean(false);
                boolean ok = false;
                try {
                    Object result = fallbackPolicy.execute(withRetry, target, method, parameters,
                            () -> applied.set(true));
                    Object r = completionStageSemantics ? unwrapAsyncResult(result) : result;
                    ok = true;
                    return r;
                } finally {
                    recorder.recordInvocation(beanClass, method, ok, applied.get(), true);
                }
            };
        }

        /** Synchronous path: runs the chain, through the fallback when there is one, and records the outcome. */
        private Object runSync(Invocation withRetry, FallbackPolicy fallbackPolicy, Object target,
                               Object[] parameters) throws Exception {
            AtomicBoolean fallbackApplied = new AtomicBoolean(false);
            boolean succeeded = false;
            try {
                Object result;
                if (fallbackPolicy == null) {
                    result = withRetry.proceed();
                } else {
                    result = fallbackPolicy.execute(withRetry, target, method, parameters,
                            () -> fallbackApplied.set(true));
                }
                succeeded = true;
                return result;
            } finally {
                recorder.recordInvocation(beanClass, method, succeeded, fallbackApplied.get(), fallbackPolicy != null);
            }
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

        return BeanClasses.userClass(target.getClass());
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
                throw Failures.asException(ee.getCause());
            }
        }
        return result;
    }
}
