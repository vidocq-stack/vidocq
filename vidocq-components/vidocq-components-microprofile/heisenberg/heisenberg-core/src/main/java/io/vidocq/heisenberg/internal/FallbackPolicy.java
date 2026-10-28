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

import java.lang.reflect.Method;
import org.eclipse.microprofile.faulttolerance.Fallback;

/**
 * MP FT 4.1 §6: runs an invocation and, when it fails with an exception the {@link FallbackConfig}
 * selects, hands the failure to the fallback method or handler.
 */
public final class FallbackPolicy {

    private final Fallback fallback;
    private final FallbackConfig config;
    private final FallbackResolver resolver;

    /**
     * @param fallback the {@code @Fallback} annotation of the guarded method
     * @param config   its effective configuration (annotation and MicroProfile Config overrides)
     * @param resolver resolves and calls the fallback method or handler
     */
    public FallbackPolicy(Fallback fallback, FallbackConfig config, FallbackResolver resolver) {
        this.fallback = fallback;
        this.config = config;
        this.resolver = resolver;
    }

    public static Object execute(
            PolicyComposer.Invocation invocation,
            Fallback fallback,
            FallbackConfig config,
            Object target,
            Method guardedMethod,
            Object[] parameters,
            FallbackResolver resolver
    ) throws Exception {
        return new FallbackPolicy(fallback, config, resolver)
                .execute(invocation, target, guardedMethod, parameters, () -> {});
    }

    /**
     * Runs {@code invocation}, falling back on a failure the configuration selects.
     *
     * @param onFallbackApplied called as soon as the fallback is invoked
     */
    public Object execute(
            PolicyComposer.Invocation invocation,
            Object target,
            Method guardedMethod,
            Object[] parameters,
            Runnable onFallbackApplied
    ) throws Exception {
        try {
            return invocation.proceed();
        } catch (Exception failure) {
            if (!config.shouldApplyFallback(failure)) {
                throw failure;
            }
            Object result;
            // MP FT 4.1 §6.2: the fallback is considered "applied" as soon as it is invoked,
            // even if the handler itself throws an exception (the metric reflects the attempt,
            // not the handler's success).
            onFallbackApplied.run();
            if (config.fallbackMethod() != null || config.fallbackHandlerClass() != null) {
                result = resolver.resolve(config, target, guardedMethod, parameters, failure);
            } else {
                result = resolver.resolve(fallback, target, guardedMethod, parameters, failure);
            }
            return result;
        }
    }
}
