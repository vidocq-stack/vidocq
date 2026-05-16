package io.vidocq.heisenberg.internal;

import java.lang.reflect.Method;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
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
        boolean asyncActive = asynchronous != null && ConfigResolver.isAsynchronousEnabled(method);

        // M7 fix : en mode @Asynchronous, la méthode retourne un CompletionStage<T> et NON une exception.
        // Per MP FT 4.1 §8.2, un stage en erreur doit déclencher retry/fallback/CB.
        // Solution : "déballer" la CompletionStage côté chaîne pour que les politiques voient l'exception.
        final Invocation effectiveInvocation;
        if (asyncActive) {
            final Invocation raw = invocation;
            effectiveInvocation = () -> unwrapAsyncResult(raw.proceed());
        } else {
            effectiveInvocation = invocation;
        }

        // State key must stay stable across proxied instances and overloaded methods.
        String beanClassName = runtimeBeanClass.getName() + "@"
                + System.identityHashCode(runtimeBeanClass.getClassLoader());
        String methodKey = method.toGenericString();

        // Construction de la chaîne de l'intérieur vers l'extérieur :
        // méthode → @Timeout → @Bulkhead → @CircuitBreaker → @Retry → @Fallback

        // Couche 1 : @Timeout — s'applique à chaque tentative individuelle (§4.1)
        // M7 : vérifier le flag enabled via config
        Timeout timeout = annotations.timeout();
        Invocation base = effectiveInvocation;
        if (timeout != null && ConfigResolver.isTimeoutEnabled(method)) {
            TimeoutConfig timeoutConfig = ConfigResolver.timeoutConfig(method, timeout);
            final Invocation untimedBase = effectiveInvocation;
            base = () -> TimeoutEngine.execute(untimedBase, timeoutConfig);
        }

        // Couche 2 : @Bulkhead (M5) — limite la concurrence
        // M7 : vérifier le flag enabled via config
        Bulkhead bulkhead = annotations.bulkhead();
        Invocation withBulkhead = base;
        if (bulkhead != null && ConfigResolver.isBulkheadEnabled(method) && bhRegistry != null) {
            BulkheadConfig bhConfig = ConfigResolver.bulkheadConfig(method, bulkhead);
            final Invocation timedBase = base;
            withBulkhead = () -> {
                BulkheadEngine engine = new BulkheadEngine(bhConfig, bhRegistry);
                if (asyncActive) {
                    return engine.executeAsync(timedBase, beanClassName, methodKey);
                }
                return engine.execute(timedBase, beanClassName, methodKey);
            };
        }

        // Couche 3 : @CircuitBreaker (M4+) — wraps bulkhead+timeout
        // M7 : vérifier le flag enabled via config
        CircuitBreaker cb = annotations.circuitBreaker();
        Invocation withCB = withBulkhead;
        if (cb != null && ConfigResolver.isCircuitBreakerEnabled(method) && cbRegistry != null) {
            CircuitBreakerConfig cbConfig = ConfigResolver.circuitBreakerConfig(method, cb);
            final Invocation withBulkheadBase = withBulkhead;
            withCB = () -> new CircuitBreakerEngine(cbConfig, cbRegistry)
                    .execute(withBulkheadBase, beanClassName, methodKey);
        }

        // Couche 4 : @Retry — plus externe pour rejouer CB/Bulkhead/Timeout selon retryOn/abortOn
        Retry retry = annotations.retry();
        Invocation withRetry = withCB;
        if (retry != null && ConfigResolver.isRetryEnabled(method)) {
            RetryConfig retryConfig = ConfigResolver.retryConfig(method, retry);
            final Invocation withCBBase = withCB;
            withRetry = () -> RetryEngine.execute(withCBBase, retryConfig);
        }
        final Invocation withRetryFinal = withRetry;

        // Couche 5 : @Fallback — couche la plus externe
        // M7 : vérifier le flag enabled via config
        // M8 §9.1 : applyOn/skipOn surchargeables via MicroProfile Config
        Fallback fallback = annotations.fallback();
        final Fallback fallbackIfEnabled = (fallback != null && ConfigResolver.isFallbackEnabled(method)) ? fallback : null;
        final FallbackConfig fallbackConfig = fallbackIfEnabled == null ? null
                : ConfigResolver.fallbackConfig(method, fallbackIfEnabled);

        // Couche 6 : @Asynchronous — si présent ET activé, tout s'exécute dans un virtual thread.
        if (asyncActive) {
            final Fallback fallbackFinal = fallbackIfEnabled;
            final FallbackConfig fallbackConfigFinal = fallbackConfig;
            final Object targetFinal = target;
            final Method methodFinal = method;
            final Object[] parametersFinal = parameters;
            final Invocation withFallback = fallbackFinal == null
                    ? withRetryFinal
                    : () -> unwrapAsyncResult(
                            FallbackPolicy.execute(withRetryFinal, fallbackFinal, fallbackConfigFinal, targetFinal, methodFinal, parametersFinal, FALLBACK_RESOLVER));

            return AsynchronousEngine.executeAsync(withFallback, method.getName());
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
        if (result instanceof Future<?> future) {
            try {
                return future.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
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
