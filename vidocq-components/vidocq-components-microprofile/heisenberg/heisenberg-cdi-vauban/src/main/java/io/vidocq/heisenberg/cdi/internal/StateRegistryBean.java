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

import io.vidocq.heisenberg.internal.CircuitBreakerState;
import io.vidocq.heisenberg.internal.CircuitBreakerStateRegistry;
import io.vidocq.heisenberg.internal.ConfigResolver;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;

/**
 * Global Circuit Breaker state registry — CDI {@code @ApplicationScoped} bean.
 *
 * <p>Stores the CLOSED/OPEN/HALF_OPEN states + success counters for all circuit breakers
 * using a stable key: {@code ClassName#methodName}.</p>
 */
@ApplicationScoped
public class StateRegistryBean implements CircuitBreakerStateRegistry {

    private static final class CircuitBreakerSnapshot {
        volatile CircuitBreakerState state = CircuitBreakerState.CLOSED;
        volatile long openedAtNanos = 0;
        final AtomicInteger successesInHalfOpen = new AtomicInteger();
        final AtomicInteger failureCount = new AtomicInteger();
    }

    private final ConcurrentHashMap<String, CircuitBreakerSnapshot> states = new ConcurrentHashMap<>();

    /**
     * A deployment starts: the policies resolved from the configuration of a previous one, in the same JVM and
     * possibly under the same {@code Config} instance, are resolved again on their next invocation.
     */
    void deploymentStarted(@Observes @Initialized(ApplicationScoped.class) Object event) {
        ConfigResolver.configurationChanged();
    }

    /**
     * Stable key for a circuit breaker.
     */
    private String key(String beanClass, String methodName) {
        return beanClass + "#" + methodName;
    }

    private CircuitBreakerSnapshot getOrCreate(String key) {
        return states.computeIfAbsent(key, k -> new CircuitBreakerSnapshot());
    }

    @Override
    public CircuitBreakerState getState(String beanClass, String methodName) {
        return getOrCreate(key(beanClass, methodName)).state;
    }

    @Override
    public void recordSuccess(String beanClass, String methodName) {
        CircuitBreakerSnapshot snap = getOrCreate(key(beanClass, methodName));
        snap.successesInHalfOpen.incrementAndGet();
    }

    @Override
    public void recordFailure(String beanClass, String methodName) {
        CircuitBreakerSnapshot snap = getOrCreate(key(beanClass, methodName));
        snap.failureCount.incrementAndGet();
    }

    @Override
    public void setOpen(String beanClass, String methodName) {
        CircuitBreakerSnapshot snap = getOrCreate(key(beanClass, methodName));
        snap.state = CircuitBreakerState.OPEN;
        snap.openedAtNanos = System.nanoTime();
    }

    @Override
    public void setClosed(String beanClass, String methodName) {
        CircuitBreakerSnapshot snap = getOrCreate(key(beanClass, methodName));
        snap.state = CircuitBreakerState.CLOSED;
        snap.successesInHalfOpen.set(0);
        snap.failureCount.set(0);
    }

    @Override
    public void setHalfOpen(String beanClass, String methodName) {
        CircuitBreakerSnapshot snap = getOrCreate(key(beanClass, methodName));
        snap.state = CircuitBreakerState.HALF_OPEN;
        snap.successesInHalfOpen.set(0);
    }

    @Override
    public long getMillisSinceOpen(String beanClass, String methodName) {
        CircuitBreakerSnapshot snap = states.get(key(beanClass, methodName));
        if (snap == null) return 0;
        return (System.nanoTime() - snap.openedAtNanos) / 1_000_000;
    }

    @Override
    public int getSuccessesInHalfOpen(String beanClass, String methodName) {
        CircuitBreakerSnapshot snap = states.get(key(beanClass, methodName));
        return snap != null ? snap.successesInHalfOpen.get() : 0;
    }
}

