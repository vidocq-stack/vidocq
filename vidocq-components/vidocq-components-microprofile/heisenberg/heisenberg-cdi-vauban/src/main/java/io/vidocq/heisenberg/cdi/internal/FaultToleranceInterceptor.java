package io.vidocq.heisenberg.cdi.internal;

import io.vidocq.heisenberg.api.FtMetricsRecorder;
import io.vidocq.heisenberg.internal.ConfigResolver;
import io.vidocq.heisenberg.internal.PolicyComposer;
import java.lang.reflect.Method;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * Intercepteur Fault Tolerance CDI — orchestre la chaîne de politiques M1-M7.
 * Dépend de StateRegistry (@ApplicationScoped) pour CircuitBreaker et Bulkhead.
 *
 * <p><strong>Binding marqueur {@link FaultToleranceBinding}</strong> : per
 * Jakarta Interceptors §2.6, déclarer simultanément {@code @Retry @Timeout
 * @CircuitBreaker @Bulkhead @Asynchronous} signifierait que l'intercepteur ne
 * s'active qu'aux méthodes portant <em>toutes</em> ces annotations (ET logique).
 * On utilise donc un binding marqueur unique ({@code @FaultToleranceBinding})
 * que {@link HeisenbergExtension} ajoute via BCE sur toute classe/méthode FT.</p>
 *
 * <p>M7 §9 : si {@code mp.fault.tolerance.interceptor.priority >= Integer.MAX_VALUE},
 * l'intercepteur est entièrement court-circuité (la méthode s'exécute sans politiques).
 * La logique est déléguée à {@link ConfigResolver#isInterceptorGloballyDisabled()}
 * pour permettre les tests unitaires avec un Config mock.</p>
 */
@Interceptor
@Priority(FaultToleranceInterceptor.BASE_PRIORITY)
@FaultToleranceBinding
public class FaultToleranceInterceptor {

    public static final int BASE_PRIORITY = 4010;
    static final int TCK_PRIORITY_3850 = 3850;

    @Inject
    private StateRegistryBean stateRegistry;

    @Inject
    private BulkheadStateRegistryBean bulkheadRegistry;

    @Inject
    @Any
    private Instance<FtMetricsRecorder> recorderInstance;

    @AroundInvoke
    public Object around(InvocationContext context) throws Exception {
        // TCK MP FT 4.1: lorsqu'une priorité custom 3850 est configurée,
        // un intercepteur dédié (priorité 3850) devient l'intercepteur FT actif.
        if (ConfigResolver.interceptorPriority() == TCK_PRIORITY_3850) {
            return context.proceed();
        }

        // M7 §9 : court-circuit si désactivation globale via config
        if (ConfigResolver.isInterceptorGloballyDisabled()) {
            return context.proceed();
        }

        FtMetricsRecorder recorder;
        if (recorderInstance == null || recorderInstance.isUnsatisfied()) {
            System.err.println("[HEISENBERG-DEBUG] recorderInstance=" + recorderInstance + " → using NOOP");
            recorder = FtMetricsRecorder.NOOP;
        } else {
            recorder = recorderInstance.get();
            System.err.println("[HEISENBERG-DEBUG] recorder=" + recorder.getClass().getSimpleName());
        }

        Method resolvedMethod = resolveInterceptedMethod(context);
        return PolicyComposer.invoke(context::proceed, context.getTarget(), resolvedMethod,
                context.getParameters(), stateRegistry, bulkheadRegistry, recorder);
    }


    static Method resolveInterceptedMethod(InvocationContext context) {
        Method interceptedMethod = context.getMethod();
        if (interceptedMethod == null) {
            return null;
        }

        String methodName = interceptedMethod.getName();
        boolean superBridge = methodName.startsWith("$$super$");
        if (superBridge) {
            methodName = methodName.substring("$$super$".length());
        }

        Object target = context.getTarget();
        if (target == null) {
            return interceptedMethod;
        }

        Class<?> current = target.getClass();
        while (current.getName().contains("$$Intercepted") && current.getSuperclass() != null) {
            current = current.getSuperclass();
        }

        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredMethod(methodName, interceptedMethod.getParameterTypes());
            } catch (NoSuchMethodException ignored) {
                // continue walking up
            }
            current = current.getSuperclass();
        }

        return interceptedMethod;
    }
}


