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
package io.vidocq.heisenberg.api;

import java.lang.reflect.Method;

/**
 * Instrumentation SPI for MicroProfile Fault Tolerance 4.1 §9 metrics.
 *
 * <p>Implementations are discovered through CDI; if none is available,
 * {@link #NOOP} is used. {@code DiracFtMetricsRecorder} (heisenberg-cdi-vauban) is
 * the standard implementation based on Dirac (MP Metrics).</p>
 *
 * <p>All methods are idempotent and thread-safe.</p>
 */
public interface FtMetricsRecorder {

    /**
     * Registers metrics for an FT-annotated method.
     * Called only once per method (on first call). Idempotent.
     *
     * @param asyncBulkhead {@code true} if @Bulkhead is in async mode (@Asynchronous present)
     */
    void register(Class<?> beanClass, Method method,
                  boolean hasRetry, boolean hasTimeout, boolean hasCircuitBreaker,
                  boolean hasBulkhead, boolean hasFallback, boolean asyncBulkhead);

    /**
     * Records the overall result of an FT invocation (after all policies).
     *
     * @param succeeded      {@code true} if the method returned a value (possibly via fallback)
     * @param fallbackApplied {@code true} if the fallback was invoked and returned successfully
     * @param fallbackDefined {@code true} if @Fallback is present on the method
     */
    void recordInvocation(Class<?> beanClass, Method method,
                          boolean succeeded, boolean fallbackApplied, boolean fallbackDefined);

    /**
     * Records the outcome of the @Retry policy.
     *
     * @param retryCount number of retry attempts (0 = no retry)
     * @param result     how the policy completed
     */
    void recordRetry(Class<?> beanClass, Method method, int retryCount, RetryResult result);

    /**
     * Records the outcome of the @Timeout policy.
     *
     * @param timedOut      {@code true} if the timeout was triggered
     * @param durationNanos execution duration in nanoseconds
     */
    void recordTimeout(Class<?> beanClass, Method method, boolean timedOut, long durationNanos);

    /**
     * Records the result of a call at the @CircuitBreaker level.
     */
    void recordCircuitBreakerCall(Class<?> beanClass, Method method, CBCallResult result);

    /**
     * Notifies a circuit breaker state transition.
     * Called whenever the state changes (CLOSED→OPEN, OPEN→HALF_OPEN, HALF_OPEN→CLOSED).
     */
    void notifyCircuitBreakerStateChange(Class<?> beanClass, Method method, CBState from, CBState to);

    /**
     * Records an invocation accepted by the @Bulkhead.
     *
     * @param waitNanos time spent in the wait queue (0 for sync mode or an immediate permit)
     * @param runNanos  execution time
     */
    void recordBulkheadAccepted(Class<?> beanClass, Method method, long waitNanos, long runNanos);

    /**
     * Records a rejection by the @Bulkhead.
     */
    void recordBulkheadRejected(Class<?> beanClass, Method method);

    /**
     * Updates the @Bulkhead "running invocations" gauge.
     *
     * @param delta +1 when an invocation starts, -1 when it ends
     */
    void bulkheadRunningDelta(Class<?> beanClass, Method method, int delta);

    /**
     * Updates the @Bulkhead "waiting invocations" gauge (async mode only).
     *
     * @param delta +1 when an invocation enters the queue, -1 when it leaves it
     */
    void bulkheadWaitingDelta(Class<?> beanClass, Method method, int delta);

    // ------------------------------------------------------------------
    // Result enums
    // ------------------------------------------------------------------

    enum RetryResult {
        VALUE_RETURNED,
        EXCEPTION_NOT_RETRYABLE,
        MAX_RETRIES_REACHED,
        MAX_DURATION_REACHED;

        public String tagValue() {
            return switch (this) {
                case VALUE_RETURNED -> "valueReturned";
                case EXCEPTION_NOT_RETRYABLE -> "exceptionNotRetryable";
                case MAX_RETRIES_REACHED -> "maxRetriesReached";
                case MAX_DURATION_REACHED -> "maxDurationReached";
            };
        }
    }

    enum CBCallResult {
        SUCCESS, FAILURE, CIRCUIT_BREAKER_OPEN;

        public String tagValue() {
            return switch (this) {
                case SUCCESS -> "success";
                case FAILURE -> "failure";
                case CIRCUIT_BREAKER_OPEN -> "circuitBreakerOpen";
            };
        }
    }

    enum CBState {
        CLOSED, OPEN, HALF_OPEN;

        public String tagValue() {
            return switch (this) {
                case CLOSED -> "closed";
                case OPEN -> "open";
                case HALF_OPEN -> "halfOpen";
            };
        }
    }

    // ------------------------------------------------------------------
    // No-op implementation (no instrumentation)
    // ------------------------------------------------------------------

    FtMetricsRecorder NOOP = new FtMetricsRecorder() {
        @Override
        public void register(Class<?> beanClass, Method method, boolean hasRetry, boolean hasTimeout,
                             boolean hasCircuitBreaker, boolean hasBulkhead, boolean hasFallback,
                             boolean asyncBulkhead) {}
        @Override
        public void recordInvocation(Class<?> beanClass, Method method, boolean succeeded,
                                     boolean fallbackApplied, boolean fallbackDefined) {}
        @Override
        public void recordRetry(Class<?> beanClass, Method method, int retryCount, RetryResult result) {}
        @Override
        public void recordTimeout(Class<?> beanClass, Method method, boolean timedOut, long durationNanos) {}
        @Override
        public void recordCircuitBreakerCall(Class<?> beanClass, Method method, CBCallResult result) {}
        @Override
        public void notifyCircuitBreakerStateChange(Class<?> beanClass, Method method, CBState from, CBState to) {}
        @Override
        public void recordBulkheadAccepted(Class<?> beanClass, Method method, long waitNanos, long runNanos) {}
        @Override
        public void recordBulkheadRejected(Class<?> beanClass, Method method) {}
        @Override
        public void bulkheadRunningDelta(Class<?> beanClass, Method method, int delta) {}
        @Override
        public void bulkheadWaitingDelta(Class<?> beanClass, Method method, int delta) {}
    };
}
