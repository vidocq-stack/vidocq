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

/**
 * Base of the metrics recorder beans whose implementation needs an optional API (MicroProfile
 * Metrics, OpenTelemetry). The bean class refers to no type of that API: it forwards to a delegate
 * that its subclass creates only once the API is known to be present, and to
 * {@link FtMetricsRecorder#NOOP} otherwise.
 *
 * <p>A container inspects every field, method and initializer of a bean class it discovers. A bean
 * class that refers to a missing API fails to link, and the container does not start (BUG-005):
 * Weld drops such a class with {@code WELD-000119}, Vauban stops on the {@code NoClassDefFoundError}.
 * Keeping the API types in a separate, non-bean class makes Fault Tolerance work with or without
 * the observability APIs, on any container.</p>
 */
abstract class DelegatingFtMetricsRecorder implements FtMetricsRecorder {

    private FtMetricsRecorder delegate = FtMetricsRecorder.NOOP;

    void delegateTo(FtMetricsRecorder delegate) {
        this.delegate = delegate;
    }

    /**
     * Whether this recorder publishes anything. Public, so that a call through the client proxy
     * reaches the contextual instance.
     */
    public boolean active() {
        return delegate != FtMetricsRecorder.NOOP;
    }

    /**
     * Whether {@code className} can be loaded by this module and used from it: on the class path,
     * that the class is there; on the module path, also that this module reads its module (an
     * optional {@code requires static} module that is not in the layer is not read).
     */
    static boolean apiPresent(String className) {
        try {
            Class<?> type = Class.forName(className, false, DelegatingFtMetricsRecorder.class.getClassLoader());
            return DelegatingFtMetricsRecorder.class.getModule().canRead(type.getModule());
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    @Override
    public void register(Class<?> beanClass, Method method,
                         boolean hasRetry, boolean hasTimeout, boolean hasCircuitBreaker,
                         boolean hasBulkhead, boolean hasFallback, boolean asyncBulkhead) {
        delegate.register(beanClass, method, hasRetry, hasTimeout, hasCircuitBreaker,
                hasBulkhead, hasFallback, asyncBulkhead);
    }

    @Override
    public void recordInvocation(Class<?> beanClass, Method method,
                                 boolean succeeded, boolean fallbackApplied, boolean fallbackDefined) {
        delegate.recordInvocation(beanClass, method, succeeded, fallbackApplied, fallbackDefined);
    }

    @Override
    public void recordRetry(Class<?> beanClass, Method method, int retryCount, RetryResult result) {
        delegate.recordRetry(beanClass, method, retryCount, result);
    }

    @Override
    public void recordTimeout(Class<?> beanClass, Method method, boolean timedOut, long durationNanos) {
        delegate.recordTimeout(beanClass, method, timedOut, durationNanos);
    }

    @Override
    public void recordCircuitBreakerCall(Class<?> beanClass, Method method, CBCallResult result) {
        delegate.recordCircuitBreakerCall(beanClass, method, result);
    }

    @Override
    public void notifyCircuitBreakerStateChange(Class<?> beanClass, Method method, CBState from, CBState to) {
        delegate.notifyCircuitBreakerStateChange(beanClass, method, from, to);
    }

    @Override
    public void recordBulkheadAccepted(Class<?> beanClass, Method method, long waitNanos, long runNanos) {
        delegate.recordBulkheadAccepted(beanClass, method, waitNanos, runNanos);
    }

    @Override
    public void recordBulkheadRejected(Class<?> beanClass, Method method) {
        delegate.recordBulkheadRejected(beanClass, method);
    }

    @Override
    public void bulkheadRunningDelta(Class<?> beanClass, Method method, int delta) {
        delegate.bulkheadRunningDelta(beanClass, method, delta);
    }

    @Override
    public void bulkheadWaitingDelta(Class<?> beanClass, Method method, int delta) {
        delegate.bulkheadWaitingDelta(beanClass, method, delta);
    }
}
