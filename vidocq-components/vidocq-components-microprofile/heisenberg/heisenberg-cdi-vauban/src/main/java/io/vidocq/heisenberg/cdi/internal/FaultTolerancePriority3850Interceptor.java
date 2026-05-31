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
 * FT interceptor dedicated to the scenario where the priority is configured to 3850 (TCK).
 *
 * <p>Since interceptor priority is static in Jakarta Interceptors,
 * this component is activated only when
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

    @Inject
    @Any
    private Instance<FtMetricsRecorder> recorderInstance;

    @AroundInvoke
    public Object around(InvocationContext context) throws Exception {
        if (ConfigResolver.interceptorPriority() != FaultToleranceInterceptor.TCK_PRIORITY_3850) {
            return context.proceed();
        }
        if (ConfigResolver.isInterceptorGloballyDisabled()) {
            return context.proceed();
        }

        FtMetricsRecorder recorder = MetricsRecorderResolver.resolve(recorderInstance);

        Method resolvedMethod = FaultToleranceInterceptor.resolveInterceptedMethod(context);
        return PolicyComposer.invoke(context::proceed, context.getTarget(), resolvedMethod,
                context.getParameters(), stateRegistry, bulkheadRegistry, recorder);
    }
}



