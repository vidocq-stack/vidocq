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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;

/**
 * What the Fault Tolerance annotations and MicroProfile Config say about one guarded method of one bean class,
 * resolved on its first invocation and kept for the next ones. It used to be read again on every invocation: six
 * annotation lookups along the class hierarchy, and the configuration keys of each policy (nine for
 * {@code @Retry}), so that a {@code @Retry} method that succeeded at once cost microseconds.
 *
 * <p>An entry belongs to the {@link Config} it was resolved with and to a {@link ConfigResolver#configurationChanged()
 * generation}: when the thread's configuration is another instance, or a deployment started since, the entry is
 * resolved again. The same {@code Config} instance can serve a new deployment with other values. Entries live in a
 * {@link ClassValue} of the bean class, so they go away with the class loader of a deployment.</p>
 *
 * <p>The circuit breaker and bulkhead also need their state registry; whether one is available is decided per
 * invocation by {@link PolicyComposer}, as before.</p>
 */
record MethodPolicies(
        Config config,
        int generation,
        boolean asynchronous,
        boolean completionStage,
        Retry retry,
        RetryConfig retryConfig,
        Timeout timeout,
        TimeoutConfig timeoutConfig,
        CircuitBreaker circuitBreaker,
        CircuitBreakerConfig circuitBreakerConfig,
        Bulkhead bulkhead,
        BulkheadConfig bulkheadConfig,
        Fallback fallback,
        FallbackConfig fallbackConfig,
        String beanClassName,
        String methodKey) {

    private static final ClassValue<ConcurrentHashMap<Method, MethodPolicies>> BY_BEAN_CLASS = new ClassValue<>() {
        @Override
        protected ConcurrentHashMap<Method, MethodPolicies> computeValue(Class<?> beanClass) {
            return new ConcurrentHashMap<>();
        }
    };

    /** The policies of {@code method} on {@code beanClass} under the current configuration. */
    static MethodPolicies of(Method method, Class<?> beanClass) {
        Config config = ConfigResolver.currentConfig();
        int generation = ConfigResolver.generation();
        ConcurrentHashMap<Method, MethodPolicies> byMethod = BY_BEAN_CLASS.get(beanClass);
        MethodPolicies policies = byMethod.get(method);
        if (policies == null || policies.config != config || policies.generation != generation) {
            policies = resolve(method, beanClass, config, generation);
            byMethod.put(method, policies);
        }
        return policies;
    }

    private static MethodPolicies resolve(Method method, Class<?> beanClass, Config config, int generation) {
        AnnotationReader.FaultToleranceAnnotations annotations = AnnotationReader.read(method, beanClass);
        Retry retry = enabled(annotations.retry(), method, beanClass, ConfigResolver.RETRY, config);
        Timeout timeout = enabled(annotations.timeout(), method, beanClass, ConfigResolver.TIMEOUT, config);
        CircuitBreaker circuitBreaker = enabled(annotations.circuitBreaker(), method, beanClass,
                ConfigResolver.CIRCUIT_BREAKER, config);
        Bulkhead bulkhead = enabled(annotations.bulkhead(), method, beanClass, ConfigResolver.BULKHEAD, config);
        Fallback fallback = enabled(annotations.fallback(), method, beanClass, ConfigResolver.FALLBACK, config);
        boolean asynchronous = enabled(annotations.asynchronous(), method, beanClass, ConfigResolver.ASYNCHRONOUS,
                config) != null;
        return new MethodPolicies(
                config,
                generation,
                asynchronous,
                CompletionStage.class.isAssignableFrom(method.getReturnType()),
                retry,
                retry == null ? null : ConfigResolver.retryConfig(method, beanClass, retry, config),
                timeout,
                timeout == null ? null : ConfigResolver.timeoutConfig(method, beanClass, timeout, config),
                circuitBreaker,
                circuitBreaker == null ? null
                        : ConfigResolver.circuitBreakerConfig(method, beanClass, circuitBreaker, config),
                bulkhead,
                bulkhead == null ? null : ConfigResolver.bulkheadConfig(method, beanClass, bulkhead, config),
                fallback,
                fallback == null ? null : ConfigResolver.fallbackConfig(method, beanClass, fallback, config),
                beanClass.getName() + "@" + System.identityHashCode(beanClass.getClassLoader()),
                method.toGenericString());
    }

    /** {@code annotation} when the method carries it and the configuration does not disable it, else {@code null}. */
    private static <A> A enabled(A annotation, Method method, Class<?> beanClass, String name, Config config) {
        return annotation != null && ConfigResolver.isEnabled(method, beanClass, name, config) ? annotation : null;
    }
}
