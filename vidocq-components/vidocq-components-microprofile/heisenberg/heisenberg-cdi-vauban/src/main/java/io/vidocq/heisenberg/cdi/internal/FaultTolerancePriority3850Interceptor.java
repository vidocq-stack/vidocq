package io.vidocq.heisenberg.cdi.internal;

import io.vidocq.heisenberg.internal.ConfigResolver;
import io.vidocq.heisenberg.internal.PolicyComposer;
import java.lang.reflect.Method;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * Intercepteur FT dédié au scénario de priorité configurée à 3850 (TCK).
 *
 * <p>La priorité des intercepteurs étant statique en Jakarta Interceptors,
 * ce composant est activé uniquement quand
 * {@code mp.fault.tolerance.interceptor.priority=3850}.</p>
 */
@Interceptor
@Priority(FaultToleranceInterceptor.TCK_PRIORITY_3850)
@FaultToleranceBinding
public class FaultTolerancePriority3850Interceptor {

    @Inject
    private StateRegistryBean stateRegistry;

    @Inject
    private BulkheadStateRegistryBean bulkheadRegistry;

    @AroundInvoke
    public Object around(InvocationContext context) throws Exception {
        if (ConfigResolver.interceptorPriority() != FaultToleranceInterceptor.TCK_PRIORITY_3850) {
            return context.proceed();
        }
        if (ConfigResolver.isInterceptorGloballyDisabled()) {
            return context.proceed();
        }

        Method resolvedMethod = FaultToleranceInterceptor.resolveInterceptedMethod(context);
        return PolicyComposer.invoke(context::proceed, context.getTarget(), resolvedMethod,
                context.getParameters(), stateRegistry, bulkheadRegistry);
    }
}



