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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * {@code @CircuitBreaker} engine — isolated from global state.
 *
 * <p>This class receives a {@link CircuitBreakerStateRegistry} to manage state
 * between invocations (state shared by all instances of a bean).
 * MP FT 4.1 §5.</p>
 */
public final class CircuitBreakerEngine {

    private final CircuitBreakerConfig config;
    private final CircuitBreakerStateRegistry registry;
    private static final ConcurrentHashMap<String, SlidingWindow> WINDOWS = new ConcurrentHashMap<>();

    private static final class SlidingWindow {
        private final ConcurrentLinkedDeque<Boolean> outcomes = new ConcurrentLinkedDeque<>();
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();

        void add(boolean failure, int maxSize) {
            outcomes.addLast(failure);
            requests.incrementAndGet();
            if (failure) {
                failures.incrementAndGet();
            }

            while (requests.get() > maxSize) {
                var removed = outcomes.pollFirst();
                if (removed == null) {
                    requests.set(0);
                    failures.set(0);
                    return;
                }
                requests.decrementAndGet();
                if (removed) {
                    failures.decrementAndGet();
                }
            }
        }

        int requests() {
            return requests.get();
        }

        int failures() {
            return failures.get();
        }

        void clear() {
            outcomes.clear();
            requests.set(0);
            failures.set(0);
        }
    }

    public CircuitBreakerEngine(CircuitBreakerConfig config, CircuitBreakerStateRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    /**
     * Executes the invocation with circuit-breaker protection.
     *
     * @param invocation   the invocation to protect
     * @param beanClass    bean class
     * @param methodName   method name
     * @return invocation result
     * @throws CircuitBreakerOpenException if the circuit is OPEN
     * @throws Exception                   if the invocation fails
     */
    public Object execute(PolicyComposer.Invocation invocation, String beanClass, String methodName) throws Exception {
        CircuitBreakerState state = registry.getState(beanClass, methodName);

        switch (state) {
            case CLOSED:
                return executeClosed(invocation, beanClass, methodName);
            case OPEN:
                return executeOpen(invocation, beanClass, methodName);
            case HALF_OPEN:
                return executeHalfOpen(invocation, beanClass, methodName);
            default:
                throw new AssertionError("Unknown state: " + state);
        }
    }

    private Object executeClosed(PolicyComposer.Invocation invocation, String beanClass, String methodName) throws Exception {
        try {
            Object result = invocation.proceed();
            recordClosedOutcome(beanClass, methodName, false);
            if (shouldOpen(beanClass, methodName)) {
                registry.setOpen(beanClass, methodName);
            }
            return result;
        } catch (Throwable failure) {
            if (shouldCountFailure(failure)) {
                registry.recordFailure(beanClass, methodName);
                recordClosedOutcome(beanClass, methodName, true);
                if (shouldOpen(beanClass, methodName)) {
                    registry.setOpen(beanClass, methodName);
                }
            } else {
                recordClosedOutcome(beanClass, methodName, false);
            }
            throw failure;
        }
    }

    private Object executeOpen(PolicyComposer.Invocation invocation, String beanClass, String methodName) throws Exception {
        // OPEN state: check whether the delay has elapsed
        long millisSinceOpen = registry.getMillisSinceOpen(beanClass, methodName);
        long delayMillis = config.delayDuration().toMillis();

        if (millisSinceOpen >= delayMillis) {
            // Transition OPEN → HALF_OPEN
            registry.setHalfOpen(beanClass, methodName);
            return executeHalfOpen(invocation, beanClass, methodName);
        }

        // Circuit is open: fail fast
        throw new CircuitBreakerOpenException(
                "Circuit breaker is OPEN for " + beanClass + "#" + methodName +
                        " (will retry in " + (delayMillis - millisSinceOpen) + " ms)"
        );
    }

    private Object executeHalfOpen(PolicyComposer.Invocation invocation, String beanClass, String methodName) throws Exception {
        try {
            Object result = invocation.proceed();
            // Success in HALF_OPEN
            registry.recordSuccess(beanClass, methodName);

            // Check whether successThreshold has been reached
            int successes = registry.getSuccessesInHalfOpen(beanClass, methodName);
            if (successes >= config.successThreshold()) {
                // Transition HALF_OPEN → CLOSED
                registry.setClosed(beanClass, methodName);
                resetWindow(beanClass, methodName);
            }

            return result;
        } catch (Throwable failure) {
            if (shouldCountFailure(failure)) {
                // Failure in HALF_OPEN → back to OPEN
                registry.setOpen(beanClass, methodName);
                resetWindow(beanClass, methodName);
            }
            throw failure;
        }
    }

    private void recordClosedOutcome(String beanClass, String methodName, boolean failure) {
        window(beanClass, methodName).add(failure, config.requestVolumeThreshold());
    }

    private boolean shouldOpen(String beanClass, String methodName) {
        var w = window(beanClass, methodName);
        int requests = w.requests();
        if (requests < config.requestVolumeThreshold()) {
            return false;
        }
        return (w.failures() / (double) requests) >= config.failureRatio();
    }

    private void resetWindow(String beanClass, String methodName) {
        window(beanClass, methodName).clear();
    }

    private SlidingWindow window(String beanClass, String methodName) {
        return WINDOWS.computeIfAbsent(windowKey(beanClass, methodName), k -> new SlidingWindow());
    }

    private String windowKey(String beanClass, String methodName) {
        return System.identityHashCode(registry) + ":" + beanClass + "#" + methodName;
    }

    private boolean shouldCountFailure(Throwable failure) {
        // skipOn takes precedence
        if (matchesAny(failure, config.skipOn())) {
            return false;
        }
        // Otherwise, failOn
        return matchesAny(failure, config.failOn());
    }

    private static boolean matchesAny(Throwable failure, Class<? extends Throwable>[] types) {
        for (Class<? extends Throwable> type : types) {
            if (type.isAssignableFrom(failure.getClass())) {
                return true;
            }
        }
        return false;
    }
}


