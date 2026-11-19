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

import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.ACCEPTED;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.BULKHEAD_CALLS_TOTAL;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.BULKHEAD_EXECUTIONS_RUNNING;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.BULKHEAD_EXECUTIONS_WAITING;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.BULKHEAD_RESULT;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.BULKHEAD_RUNNING_DURATION;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.BULKHEAD_WAITING_DURATION;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.CIRCUIT_BREAKER_CALLS_TOTAL;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.CIRCUIT_BREAKER_OPENED_TOTAL;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.CIRCUIT_BREAKER_RESULT;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.CIRCUIT_BREAKER_STATE_TOTAL;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.EXCEPTION_THROWN;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.FALLBACK;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.INVOCATIONS_TOTAL;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.METHOD;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.REJECTED;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.RESULT;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.RETRIED;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.RETRY_CALLS_TOTAL;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.RETRY_RESULT;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.RETRY_RETRIES_TOTAL;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.STATE;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.TIMED_OUT;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.TIMEOUT_CALLS_TOTAL;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.TIMEOUT_EXECUTION_DURATION;
import static io.vidocq.heisenberg.cdi.internal.FtMetricNames.VALUE_RETURNED;

import io.vidocq.heisenberg.api.FtMetricsRecorder;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.Tag;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MicroProfile Fault Tolerance 4.1 §9 metrics recorder based on Dirac (MP Metrics).
 *
 * <p>Publishes all FT metrics in the {@code base} scope registry (MP Fault
 * Tolerance 4.1 §9 — the FT TCK reads them back through
 * {@code @RegistryType(BASE)}, which MP Metrics 5.1 maps to the base-scope
 * registry):
 * {@code ft.invocations.total}, {@code ft.retry.calls.total}, {@code ft.retry.retries.total},
 * {@code ft.timeout.calls.total}, {@code ft.timeout.executionDuration},
 * {@code ft.circuitbreaker.calls.total}, {@code ft.circuitbreaker.state.total},
 * {@code ft.circuitbreaker.opened.total}, {@code ft.bulkhead.calls.total},
 * {@code ft.bulkhead.executionsRunning}, {@code ft.bulkhead.executionsWaiting},
 * {@code ft.bulkhead.runningDuration}, {@code ft.bulkhead.waitingDuration}.</p>
 *
 * <p>Registration is idempotent: a first call creates the metrics with value 0,
 * subsequent calls reuse the same instances.</p>
 *
 * <p>Not a bean: {@link DiracFtMetricsRecorder} creates it only when the MicroProfile Metrics API
 * is present, so the bean itself never refers to a Metrics type (BUG-005).</p>
 */
final class DiracFtMetrics implements FtMetricsRecorder {

    private final MetricRegistry registry;

    DiracFtMetrics(MetricRegistry registry) {
        this.registry = registry;
    }

    /**
     * The recorder for the registry that {@code baseRegistry} selects, or
     * {@link FtMetricsRecorder#NOOP} when the Metrics API is there but no registry bean is (an API
     * jar without an implementation).
     *
     * @param baseRegistry an injection point qualified {@code @RegistryScope(scope = BASE_SCOPE)}:
     *        {@code RegistryScope.scope} is {@code @Nonbinding}, and the registry producer reads it
     *        from the annotated injection point, which a qualifier passed to {@code select} is not
     */
    static FtMetricsRecorder create(Instance<Object> baseRegistry) {
        Instance<MetricRegistry> registry = baseRegistry.select(MetricRegistry.class);
        return registry.isResolvable() ? new DiracFtMetrics(registry.get()) : FtMetricsRecorder.NOOP;
    }

    // Idempotency guard: method tag → registered
    private final ConcurrentHashMap<String, Boolean> registered = new ConcurrentHashMap<>();

    // CB state time trackers: method tag → tracker
    private final ConcurrentHashMap<String, CBStateTracker> cbStateTrackers = new ConcurrentHashMap<>();

    // Bulkhead running/waiting gauge counters: method tag → counter
    private final ConcurrentHashMap<String, AtomicLong> bulkheadRunning = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> bulkheadWaiting = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // register()
    // ------------------------------------------------------------------

    @Override
    public void register(Class<?> beanClass, Method method,
                         boolean hasRetry, boolean hasTimeout, boolean hasCircuitBreaker,
                         boolean hasBulkhead, boolean hasFallback, boolean asyncBulkhead) {
        if (!isMetricsEnabled()) return;
        String key = methodKey(beanClass, method);
        if (registered.putIfAbsent(key, Boolean.TRUE) != null) return;

        Tag methodTag = methodTag(beanClass, method);
        registerInvocations(methodTag, hasFallback);
        if (hasRetry) {
            registerRetry(methodTag);
        }
        if (hasTimeout) {
            registerTimeout(methodTag);
        }
        if (hasCircuitBreaker) {
            registerCircuitBreaker(key, methodTag);
        }
        if (hasBulkhead) {
            registerBulkhead(key, methodTag, asyncBulkhead);
        }
    }

    /** ft.invocations.total — every (result, fallback) combination. */
    private void registerInvocations(Tag methodTag, boolean hasFallback) {
        for (String result : List.of(VALUE_RETURNED, EXCEPTION_THROWN)) {
            for (String fb : FtMetricNames.fallbackValues(hasFallback)) {
                registry.counter(INVOCATIONS_TOTAL, methodTag, new Tag(RESULT, result), new Tag(FALLBACK, fb));
            }
        }
    }

    private void registerRetry(Tag methodTag) {
        // ft.retry.calls.total — all (retried, retryResult) combinations
        for (boolean retried : new boolean[]{false, true}) {
            for (RetryResult rr : FtMetricNames.retryResults(retried)) {
                registry.counter(RETRY_CALLS_TOTAL, methodTag,
                        new Tag(RETRIED, String.valueOf(retried)),
                        new Tag(RETRY_RESULT, rr.tagValue()));
            }
        }
        // ft.retry.retries.total
        registry.counter(RETRY_RETRIES_TOTAL, methodTag);
    }

    private void registerTimeout(Tag methodTag) {
        // ft.timeout.calls.total
        registry.counter(TIMEOUT_CALLS_TOTAL, methodTag, new Tag(TIMED_OUT, "true"));
        registry.counter(TIMEOUT_CALLS_TOTAL, methodTag, new Tag(TIMED_OUT, "false"));
        // ft.timeout.executionDuration
        registry.histogram(nanoseconds(TIMEOUT_EXECUTION_DURATION), methodTag);
    }

    private void registerCircuitBreaker(String key, Tag methodTag) {
        // ft.circuitbreaker.calls.total
        for (CBCallResult result : CBCallResult.values()) {
            registry.counter(CIRCUIT_BREAKER_CALLS_TOTAL, methodTag, new Tag(CIRCUIT_BREAKER_RESULT, result.tagValue()));
        }
        // ft.circuitbreaker.opened.total
        registry.counter(CIRCUIT_BREAKER_OPENED_TOTAL, methodTag);
        // ft.circuitbreaker.state.total — 3 gauges (one per state)
        CBStateTracker tracker = cbStateTrackers.computeIfAbsent(key, k -> new CBStateTracker());
        Metadata stateMeta = nanoseconds(CIRCUIT_BREAKER_STATE_TOTAL);
        for (CBState state : CBState.values()) {
            registry.gauge(stateMeta, tracker, t -> t.nanos(state), methodTag, new Tag(STATE, state.tagValue()));
        }
    }

    private void registerBulkhead(String key, Tag methodTag, boolean asyncBulkhead) {
        // ft.bulkhead.calls.total
        registry.counter(BULKHEAD_CALLS_TOTAL, methodTag, new Tag(BULKHEAD_RESULT, ACCEPTED));
        registry.counter(BULKHEAD_CALLS_TOTAL, methodTag, new Tag(BULKHEAD_RESULT, REJECTED));
        // ft.bulkhead.executionsRunning gauge
        AtomicLong running = bulkheadRunning.computeIfAbsent(key, k -> new AtomicLong(0));
        registry.gauge(BULKHEAD_EXECUTIONS_RUNNING, running, AtomicLong::get, methodTag);
        // ft.bulkhead.runningDuration histogram
        registry.histogram(nanoseconds(BULKHEAD_RUNNING_DURATION), methodTag);

        if (asyncBulkhead) {
            // ft.bulkhead.executionsWaiting gauge
            AtomicLong waiting = bulkheadWaiting.computeIfAbsent(key, k -> new AtomicLong(0));
            registry.gauge(BULKHEAD_EXECUTIONS_WAITING, waiting, AtomicLong::get, methodTag);
            // ft.bulkhead.waitingDuration histogram
            registry.histogram(nanoseconds(BULKHEAD_WAITING_DURATION), methodTag);
        }
    }

    // ------------------------------------------------------------------
    // Invocation
    // ------------------------------------------------------------------

    @Override
    public void recordInvocation(Class<?> beanClass, Method method,
                                 boolean succeeded, boolean fallbackApplied, boolean fallbackDefined) {
        if (!isMetricsEnabled()) return;
        Tag methodTag = methodTag(beanClass, method);
        String result = succeeded ? VALUE_RETURNED : EXCEPTION_THROWN;
        String fb = FtMetricNames.fallbackValue(fallbackDefined, fallbackApplied);
        Counter c = registry.counter(INVOCATIONS_TOTAL, methodTag,
                new Tag(RESULT, result), new Tag(FALLBACK, fb));
        c.inc();
    }

    // ------------------------------------------------------------------
    // Retry
    // ------------------------------------------------------------------

    @Override
    public void recordRetry(Class<?> beanClass, Method method, int retryCount, RetryResult result) {
        if (!isMetricsEnabled()) return;
        Tag methodTag = methodTag(beanClass, method);
        boolean retried = retryCount > 0;
        registry.counter(RETRY_CALLS_TOTAL, methodTag,
                new Tag(RETRIED, String.valueOf(retried)),
                new Tag(RETRY_RESULT, result.tagValue())).inc();
        if (retryCount > 0) {
            registry.counter(RETRY_RETRIES_TOTAL, methodTag).inc(retryCount);
        }
    }

    // ------------------------------------------------------------------
    // Timeout
    // ------------------------------------------------------------------

    @Override
    public void recordTimeout(Class<?> beanClass, Method method, boolean timedOut, long durationNanos) {
        if (!isMetricsEnabled()) return;
        Tag methodTag = methodTag(beanClass, method);
        registry.counter(TIMEOUT_CALLS_TOTAL, methodTag,
                new Tag(TIMED_OUT, String.valueOf(timedOut))).inc();
        registry.histogram(nanoseconds(TIMEOUT_EXECUTION_DURATION), methodTag).update(durationNanos);
    }

    // ------------------------------------------------------------------
    // Circuit Breaker
    // ------------------------------------------------------------------

    @Override
    public void recordCircuitBreakerCall(Class<?> beanClass, Method method, CBCallResult result) {
        if (!isMetricsEnabled()) return;
        Tag methodTag = methodTag(beanClass, method);
        registry.counter(CIRCUIT_BREAKER_CALLS_TOTAL, methodTag,
                new Tag(CIRCUIT_BREAKER_RESULT, result.tagValue())).inc();
    }

    @Override
    public void notifyCircuitBreakerStateChange(Class<?> beanClass, Method method, CBState from, CBState to) {
        if (!isMetricsEnabled()) return;
        String key = methodKey(beanClass, method);
        CBStateTracker tracker = cbStateTrackers.get(key);
        if (tracker != null) {
            tracker.transition(to);
        }
        if (to == CBState.OPEN) {
            registry.counter(CIRCUIT_BREAKER_OPENED_TOTAL, methodTag(beanClass, method)).inc();
        }
    }

    // ------------------------------------------------------------------
    // Bulkhead
    // ------------------------------------------------------------------

    @Override
    public void recordBulkheadAccepted(Class<?> beanClass, Method method, long waitNanos, long runNanos) {
        if (!isMetricsEnabled()) return;
        String key = methodKey(beanClass, method);
        Tag methodTag = methodTag(beanClass, method);
        registry.counter(BULKHEAD_CALLS_TOTAL, methodTag,
                new Tag(BULKHEAD_RESULT, ACCEPTED)).inc();
        registry.histogram(nanoseconds(BULKHEAD_RUNNING_DURATION), methodTag).update(runNanos);
        // In async bulkhead mode, waitingDuration is reported for every accepted execution,
        // including immediate acquisitions (0ns), so histogram counts stay consistent with
        // the calls.total counter. In sync mode, only emit if we actually tracked waiting.
        if (bulkheadWaiting.containsKey(key)) {
            registry.histogram(nanoseconds(BULKHEAD_WAITING_DURATION), methodTag).update(Math.max(0L, waitNanos));
        }
    }

    @Override
    public void recordBulkheadRejected(Class<?> beanClass, Method method) {
        if (!isMetricsEnabled()) return;
        registry.counter(BULKHEAD_CALLS_TOTAL, methodTag(beanClass, method),
                new Tag(BULKHEAD_RESULT, REJECTED)).inc();
    }

    @Override
    public void bulkheadRunningDelta(Class<?> beanClass, Method method, int delta) {
        if (!isMetricsEnabled()) return;
        AtomicLong counter = bulkheadRunning.get(methodKey(beanClass, method));
        if (counter != null) counter.addAndGet(delta);
    }

    @Override
    public void bulkheadWaitingDelta(Class<?> beanClass, Method method, int delta) {
        if (!isMetricsEnabled()) return;
        AtomicLong counter = bulkheadWaiting.get(methodKey(beanClass, method));
        if (counter != null) counter.addAndGet(delta);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The metadata of a metric measured in nanoseconds. */
    private static Metadata nanoseconds(String name) {
        return Metadata.builder()
                .withName(name)
                .withUnit(MetricUnits.NANOSECONDS)
                .build();
    }

    private static String methodKey(Class<?> beanClass, Method method) {
        return beanClass.getName() + "." + method.getName();
    }

    private static Tag methodTag(Class<?> beanClass, Method method) {
        return new Tag(METHOD, beanClass.getName() + "." + method.getName());
    }

    private boolean isMetricsEnabled() {
        try {
            return ConfigProvider.getConfig()
                    .getOptionalValue("MP_Fault_Tolerance_Metrics_Enabled", Boolean.class)
                    .orElse(Boolean.TRUE);
        } catch (Exception _) {
            return true;
        }
    }

    // ------------------------------------------------------------------
    // CB state time tracker
    // ------------------------------------------------------------------

    private static final class CBStateTracker {
        private volatile FtMetricsRecorder.CBState currentState = FtMetricsRecorder.CBState.CLOSED;
        private volatile long stateEnteredAt = System.nanoTime();
        private final long[] accNanos = new long[3];

        synchronized long nanos(FtMetricsRecorder.CBState queryState) {
            long acc = accNanos[queryState.ordinal()];
            if (currentState == queryState) {
                acc += System.nanoTime() - stateEnteredAt;
            }
            return acc;
        }

        synchronized void transition(FtMetricsRecorder.CBState newState) {
            if (newState == currentState) return;
            long now = System.nanoTime();
            accNanos[currentState.ordinal()] += now - stateEnteredAt;
            currentState = newState;
            stateEnteredAt = now;
        }
    }
}
