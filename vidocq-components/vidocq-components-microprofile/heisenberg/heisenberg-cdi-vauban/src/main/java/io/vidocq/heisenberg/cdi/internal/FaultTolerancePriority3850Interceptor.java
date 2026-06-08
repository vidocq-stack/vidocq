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

    // Package-private (not private): see FaultToleranceInterceptor — in-package putfield by the
    // generated _VaubanComponents, no `opens` needed on the module path.
    @Inject
    StateRegistryBean stateRegistry;

    @Inject
    BulkheadStateRegistryBean bulkheadRegistry;

    @Inject
    @Any
    Instance<FtMetricsRecorder> recorderInstance;

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



