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
package io.vidocq.heisenberg.cdi.internal;

import io.vidocq.heisenberg.api.FtMetricsRecorder;
import io.vidocq.heisenberg.internal.AnnotationReader;
import io.vidocq.heisenberg.internal.ConfigResolver;
import io.vidocq.heisenberg.internal.PolicyComposer;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * CDI Fault Tolerance interceptor — orchestrates the M1-M7 policy chain.
 * Depends on StateRegistry (@ApplicationScoped) for CircuitBreaker and Bulkhead.
 *
 * <p><strong>Marker binding {@link FaultToleranceBinding}</strong>: per
 * Jakarta Interceptors §2.6, declaring {@code @Retry @Timeout
 * @CircuitBreaker @Bulkhead @Asynchronous} simultaneously would mean that the interceptor
 * activates only on methods carrying <em>all</em> these annotations (logical AND).
 * We therefore use a single marker binding ({@code @FaultToleranceBinding})
 * that {@link HeisenbergExtension} adds via BCE on every FT class/method.</p>
 *
 * <p>M7 §9: if {@code mp.fault.tolerance.interceptor.priority >= Integer.MAX_VALUE},
 * the interceptor is completely short-circuited (the method executes without policies).
 * The logic is delegated to {@link ConfigResolver#isInterceptorGloballyDisabled()}
 * to allow unit tests with a mock Config.</p>
 */
@Interceptor
@Priority(FaultToleranceInterceptor.BASE_PRIORITY)
@FaultToleranceBinding
public class FaultToleranceInterceptor {

    public static final int BASE_PRIORITY = 4010;
    static final int TCK_PRIORITY_3850 = 3850;

    // Package-private (not private): the APT-generated _VaubanComponents.injectField writes these
    // with an in-package putfield, so the container needs no `opens … to io.vidocq.vauban.core` on
    // the strict module path. Proven by heisenberg-cdi-vauban-jpms-it.
    @Inject
    StateRegistryBean stateRegistry;

    @Inject
    BulkheadStateRegistryBean bulkheadRegistry;

    @Inject
    @Any
    Instance<FtMetricsRecorder> recorderInstance;

    @AroundInvoke
    public Object around(InvocationContext context) throws Exception {
        // TCK MP FT 4.1: when a custom 3850 priority is configured,
        // a dedicated interceptor (priority 3850) becomes the active FT interceptor.
        if (ConfigResolver.interceptorPriority() == TCK_PRIORITY_3850) {
            return context.proceed();
        }

        // M7 §9: short-circuit if globally disabled via config
        if (ConfigResolver.isInterceptorGloballyDisabled()) {
            return context.proceed();
        }

        FtMetricsRecorder recorder = MetricsRecorderResolver.resolve(recorderInstance);

        Method resolvedMethod = resolveInterceptedMethod(context);
        registerMetricsOnce(recorder, context.getTarget(), resolvedMethod);
        return PolicyComposer.invoke(context::proceed, context.getTarget(), resolvedMethod,
                context.getParameters(), stateRegistry, bulkheadRegistry, recorder);
    }

    /**
     * MP FT 4.1 §9/§10: pre-registers the metrics of an FT method as early as possible (on
     * the first interception). This materializes the OTel counters with their unit,
     * so that the TCK {@code testMetricUnits} tests that inspect metadata
     * via {@code InMemoryMetricReader.getUnit(...)} can find the metrics even when
     * no increment has yet occurred for certain attribute series.
     */
    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();

    private static void registerMetricsOnce(FtMetricsRecorder recorder, Object target, Method method) {
        if (recorder == null || method == null) return;
        Class<?> beanClass = target != null ? target.getClass() : method.getDeclaringClass();
        // Unwrap intercepted classes (Vauban: $$Intercepted)
        while (beanClass.getName().contains("$$Intercepted") && beanClass.getSuperclass() != null) {
            beanClass = beanClass.getSuperclass();
        }
        String key = beanClass.getName() + "#" + method.getName();
        if (!REGISTERED.add(key)) return;
        AnnotationReader.FaultToleranceAnnotations ann = AnnotationReader.read(method, beanClass);
        recorder.register(beanClass, method,
                ann.retry() != null,
                ann.timeout() != null,
                ann.circuitBreaker() != null,
                ann.bulkhead() != null,
                ann.fallback() != null,
                ann.bulkhead() != null && ann.asynchronous() != null);
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


