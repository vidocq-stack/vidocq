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

import java.lang.reflect.Method;
import java.util.List;

/**
 * Composite recorder: fan-out to a list of delegate recorders.
 *
 * <p>Allows simultaneous publication to the two registries defined by the spec:
 * §9 (MP Metrics via {@link DiracFtMetricsRecorder}) and §10 (OpenTelemetry via
 * {@link OtelFtMetricsRecorder}).</p>
 *
 * <p>Each delegate is invoked in list order. An exception in one of them
 * is caught and ignored — instrumentation must never propagate a failure
 * into the business execution path.</p>
 */
final class CompositeFtMetricsRecorder implements FtMetricsRecorder {

    private final List<FtMetricsRecorder> delegates;

    CompositeFtMetricsRecorder(List<FtMetricsRecorder> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    private interface Action {
        void run(FtMetricsRecorder r);
    }

    private void fanOut(Action action) {
        for (FtMetricsRecorder r : delegates) {
            try {
                action.run(r);
            } catch (Throwable ignored) {
                // l'instrumentation ne doit jamais casser le chemin mtier
            }
        }
    }

    @Override
    public void register(Class<?> beanClass, Method method,
                         boolean hasRetry, boolean hasTimeout, boolean hasCircuitBreaker,
                         boolean hasBulkhead, boolean hasFallback, boolean asyncBulkhead) {
        fanOut(r -> r.register(beanClass, method, hasRetry, hasTimeout, hasCircuitBreaker,
                hasBulkhead, hasFallback, asyncBulkhead));
    }

    @Override
    public void recordInvocation(Class<?> beanClass, Method method,
                                 boolean succeeded, boolean fallbackApplied, boolean fallbackDefined) {
        fanOut(r -> r.recordInvocation(beanClass, method, succeeded, fallbackApplied, fallbackDefined));
    }

    @Override
    public void recordRetry(Class<?> beanClass, Method method, int retryCount, RetryResult result) {
        fanOut(r -> r.recordRetry(beanClass, method, retryCount, result));
    }

    @Override
    public void recordTimeout(Class<?> beanClass, Method method, boolean timedOut, long durationNanos) {
        fanOut(r -> r.recordTimeout(beanClass, method, timedOut, durationNanos));
    }

    @Override
    public void recordCircuitBreakerCall(Class<?> beanClass, Method method, CBCallResult result) {
        fanOut(r -> r.recordCircuitBreakerCall(beanClass, method, result));
    }

    @Override
    public void notifyCircuitBreakerStateChange(Class<?> beanClass, Method method, CBState from, CBState to) {
        fanOut(r -> r.notifyCircuitBreakerStateChange(beanClass, method, from, to));
    }

    @Override
    public void recordBulkheadAccepted(Class<?> beanClass, Method method, long waitNanos, long runNanos) {
        fanOut(r -> r.recordBulkheadAccepted(beanClass, method, waitNanos, runNanos));
    }

    @Override
    public void recordBulkheadRejected(Class<?> beanClass, Method method) {
        fanOut(r -> r.recordBulkheadRejected(beanClass, method));
    }

    @Override
    public void bulkheadRunningDelta(Class<?> beanClass, Method method, int delta) {
        fanOut(r -> r.bulkheadRunningDelta(beanClass, method, delta));
    }

    @Override
    public void bulkheadWaitingDelta(Class<?> beanClass, Method method, int delta) {
        fanOut(r -> r.bulkheadWaitingDelta(beanClass, method, delta));
    }
}

