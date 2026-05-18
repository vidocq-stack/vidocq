package io.vidocq.heisenberg.internal;

import java.lang.reflect.Method;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.Asynchronous;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;

/**
 * Point d'entrée du moteur Heisenberg — construit et exécute la chaîne de politiques
 * dans l'ordre défini par la spec MicroProfile Fault Tolerance 4.1 §2.5 :
 * {@code @Fallback → @CircuitBreaker → @Bulkhead → @Timeout → @Retry → méthode}.
 *
 * <p>Chaîne complète implémentée (M1-M7) : @Fallback → @CircuitBreaker → @Bulkhead → @Timeout → @Retry,
 * éventuellement enveloppée par @Asynchronous (virtual thread).</p>
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
        return invoke(invocation, target, method, parameters, null, null);
    }

    /**
     * Variante avec registries optionnels pour CircuitBreaker et Bulkhead (M4+).
     */
    public static Object invoke(Invocation invocation, Object target, Method method, Object[] parameters,
                                CircuitBreakerStateRegistry cbRegistry, BulkheadStateRegistry bhRegistry) throws Exception {
        if (method == null) {
            return invocation.proceed();
        }

        Class<?> runtimeBeanClass = resolveRuntimeBeanClass(target, method);
        AnnotationReader.FaultToleranceAnnotations annotations = AnnotationReader.read(method, runtimeBeanClass);

        // M7 — @Asynchronous : §9.1 "If the @Asynchronous annotation is disabled, the method is treated
        // as a synchronous method." → si désactivé via config, on ignore complètement la branche async
        // ET on n'unwrap pas le CompletionStage retourné par la méthode.
        Asynchronous asynchronous = annotations.asynchronous();
        boolean asyncActive = asynchronous != null && ConfigResolver.isAsynchronousEnabled(method, runtimeBeanClass);
        boolean completionStageSemantics = CompletionStage.class.isAssignableFrom(method.getReturnType());
        AtomicBoolean asyncInvocationStarted = asyncActive ? new AtomicBoolean(false) : null;

        // M7 fix : en mode @Asynchronous, la méthode retourne un CompletionStage<T> et NON une exception.
        // Per MP FT 4.1 §8.2, un stage en erreur doit déclencher retry/fallback/CB.
        // Solution : "déballer" la CompletionStage côté chaîne pour que les politiques voient l'exception.
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

        // State key must stay stable across proxied instances and overloaded methods.
        String beanClassName = runtimeBeanClass.getName() + "@"
                + System.identityHashCode(runtimeBeanClass.getClassLoader());
        String methodKey = method.toGenericString();

        // Construction de la chaîne de l'intérieur vers l'extérieur, conforme MP FT 4.1 §2.5 :
        // Outer→inner : Fallback → Retry → CB → Timeout → Bulkhead → method.
        // Note : Timeout enveloppe Bulkhead pour que le temps passé en file d'attente
        // (mode async @Bulkhead waitingTaskQueue) soit compté dans la deadline du @Timeout.
        // Spec §2.5 : « the Bulkhead queue counts towards the timeout duration ».

        // Couche 1 (la plus interne) : @Bulkhead — limite la concurrence
        Bulkhead bulkhead = annotations.bulkhead();
        Invocation base = effectiveInvocation;
        if (bulkhead != null && ConfigResolver.isBulkheadEnabled(method, runtimeBeanClass) && bhRegistry != null) {
            BulkheadConfig bhConfig = ConfigResolver.bulkheadConfig(method, runtimeBeanClass, bulkhead);
            final Invocation rawBase = effectiveInvocation;
            base = () -> {
                BulkheadEngine engine = new BulkheadEngine(bhConfig, bhRegistry);
                if (asyncActive) {
                    return engine.executeAsync(rawBase, beanClassName, methodKey);
                }
                return engine.execute(rawBase, beanClassName, methodKey);
            };
        }

        // Couche 2 : @Timeout — enveloppe Bulkhead pour comptabiliser le temps en queue
        Timeout timeout = annotations.timeout();
        Invocation withTimeout = base;
        if (timeout != null && ConfigResolver.isTimeoutEnabled(method, runtimeBeanClass)) {
            TimeoutConfig timeoutConfig = ConfigResolver.timeoutConfig(method, runtimeBeanClass, timeout);
            final Invocation bulkheadBase = base;
            final boolean asyncCall = asyncActive;
            withTimeout = () -> TimeoutEngine.execute(bulkheadBase, timeoutConfig, asyncCall);
        }

        // Couche 3 : @CircuitBreaker — voit chaque tentative individuellement
        CircuitBreaker cb = annotations.circuitBreaker();
        Invocation withCB = withTimeout;
        if (cb != null && ConfigResolver.isCircuitBreakerEnabled(method, runtimeBeanClass) && cbRegistry != null) {
            CircuitBreakerConfig cbConfig = ConfigResolver.circuitBreakerConfig(method, runtimeBeanClass, cb);
            final Invocation timeoutBase = withTimeout;
            withCB = () -> new CircuitBreakerEngine(cbConfig, cbRegistry)
                    .execute(timeoutBase, beanClassName, methodKey);
        }

        // Couche 4 : @Retry — plus externe pour rejouer CB/Bulkhead/Timeout selon retryOn/abortOn
        Retry retry = annotations.retry();
        Invocation withRetry = withCB;
        if (retry != null && ConfigResolver.isRetryEnabled(method, runtimeBeanClass)) {
            RetryConfig retryConfig = ConfigResolver.retryConfig(method, runtimeBeanClass, retry);
            final Invocation withCBBase = withCB;
            withRetry = () -> RetryEngine.execute(withCBBase, retryConfig);
        }
        final Invocation withRetryFinal = withRetry;

        // Couche 5 : @Fallback — couche la plus externe
        // M7 : vérifier le flag enabled via config
        // M8 §9.1 : applyOn/skipOn surchargeables via MicroProfile Config
        Fallback fallback = annotations.fallback();
        final Fallback fallbackIfEnabled = (fallback != null && ConfigResolver.isFallbackEnabled(method, runtimeBeanClass)) ? fallback : null;
        final FallbackConfig fallbackConfig = fallbackIfEnabled == null ? null
                : ConfigResolver.fallbackConfig(method, runtimeBeanClass, fallbackIfEnabled);

        // Couche 6 : @Asynchronous — si présent ET activé, tout s'exécute dans un virtual thread.
        if (asyncActive) {
            final Fallback fallbackFinal = fallbackIfEnabled;
            final FallbackConfig fallbackConfigFinal = fallbackConfig;
            final Object targetFinal = target;
            final Method methodFinal = method;
            final Object[] parametersFinal = parameters;
            final Invocation withFallback = fallbackFinal == null
                    ? withRetryFinal
                    : completionStageSemantics
                    ? () -> unwrapAsyncResult(
                            FallbackPolicy.execute(withRetryFinal, fallbackFinal, fallbackConfigFinal, targetFinal, methodFinal, parametersFinal, FALLBACK_RESOLVER))
                    : () -> FallbackPolicy.execute(withRetryFinal, fallbackFinal, fallbackConfigFinal, targetFinal, methodFinal, parametersFinal, FALLBACK_RESOLVER);

            return AsynchronousEngine.executeAsync(withFallback, method.getName(), asyncInvocationStarted);
        }

        if (fallbackIfEnabled == null) {
            return withRetry.proceed();
        }

        return FallbackPolicy.execute(withRetry, fallbackIfEnabled, fallbackConfig, target, method, parameters, FALLBACK_RESOLVER);
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
     * Déballe le résultat éventuellement asynchrone d'une invocation : si le résultat est un
     * {@link CompletionStage} ou un {@link Future}, attend sa complétion et propage l'exception
     * causale telle quelle pour que les politiques (retry/CB/fallback) la voient (§8.2).
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
