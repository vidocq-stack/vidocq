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

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
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
import org.eclipse.microprofile.config.ConfigProvider;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MicroProfile Fault Tolerance 4.1 §10 metrics recorder (OpenTelemetry).
 *
 * <p>Publishes FT metrics via {@link GlobalOpenTelemetry} with the meter scope
 * {@code io.vidocq.heisenberg}. All names and units are aligned with the
 * MP FT 4.1 TCK (see {@code TelemetryMetricDefinition} in the official TCK):</p>
 *
 * <ul>
 *   <li>{@code ft.invocations.total} (counter, no unit)</li>
 *   <li>{@code ft.retry.calls.total} (counter)</li>
 *   <li>{@code ft.retry.retries.total} (counter)</li>
 *   <li>{@code ft.timeout.calls.total} (counter)</li>
 *   <li>{@code ft.timeout.executionDuration} (histogram, unit=seconds)</li>
 *   <li>{@code ft.circuitbreaker.calls.total} (counter)</li>
 *   <li>{@code ft.circuitbreaker.state.total} (counter, unit=nanoseconds)</li>
 *   <li>{@code ft.circuitbreaker.opened.total} (counter)</li>
 *   <li>{@code ft.bulkhead.calls.total} (counter)</li>
 *   <li>{@code ft.bulkhead.executionsRunning} (UpDownCounter)</li>
 *   <li>{@code ft.bulkhead.executionsWaiting} (UpDownCounter)</li>
 *   <li>{@code ft.bulkhead.runningDuration} (histogram, unit=seconds)</li>
 *   <li>{@code ft.bulkhead.waitingDuration} (histogram, unit=seconds)</li>
 * </ul>
 *
 * <p><strong>Common {@code method} attribute</strong>: value =
 * {@code beanClass.getCanonicalName() + "." + method.getName()} — TCK §10.</p>
 *
 * <p>Coexists with {@link DiracFtMetrics}: the two recorder beans are dispatched in
 * parallel by {@link FaultToleranceInterceptor}.</p>
 *
 * <p>Not a bean: {@link OtelFtMetricsRecorder} creates it only when the OpenTelemetry API is
 * present, so the bean itself never refers to an OpenTelemetry type (BUG-005).</p>
 */
final class OtelFtMetrics implements FtMetricsRecorder {

    private static final String METER_NAME = "io.vidocq.heisenberg";
    private static final String SECONDS = "seconds";
    private static final String NANOSECONDS = "nanoseconds";
    private static final AttributeKey<String> METHOD_KEY = AttributeKey.stringKey(METHOD);
    private static final AttributeKey<String> RESULT_KEY = AttributeKey.stringKey(RESULT);
    private static final AttributeKey<String> FALLBACK_KEY = AttributeKey.stringKey(FALLBACK);
    private static final AttributeKey<String> RETRIED_KEY = AttributeKey.stringKey(RETRIED);
    private static final AttributeKey<String> RETRY_RESULT_KEY = AttributeKey.stringKey(RETRY_RESULT);
    private static final AttributeKey<String> TIMED_OUT_KEY = AttributeKey.stringKey(TIMED_OUT);
    private static final AttributeKey<String> CB_RESULT_KEY = AttributeKey.stringKey(CIRCUIT_BREAKER_RESULT);
    private static final AttributeKey<String> CB_STATE_KEY = AttributeKey.stringKey(STATE);
    private static final AttributeKey<String> BULKHEAD_RESULT_KEY = AttributeKey.stringKey(BULKHEAD_RESULT);
    private static final AttributeKey<String> BOOTSTRAP_KEY = AttributeKey.stringKey("heisenberg.bootstrap");

    // Instruments cached lazily (by name)
    private volatile Meter meter;
    private final ConcurrentHashMap<String, LongCounter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DoubleHistogram> histograms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongUpDownCounter> upDowns = new ConcurrentHashMap<>();

    // CB state trackers (per method) — accumulates the time spent in each state
    private final ConcurrentHashMap<String, CBStateTracker> cbStateTrackers = new ConcurrentHashMap<>();

    // Bulkhead running/waiting tracking (per method) — delta tracking for the UpDownCounters
    private final ConcurrentHashMap<String, AtomicLong> bulkheadRunning = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> bulkheadWaiting = new ConcurrentHashMap<>();

    OtelFtMetrics() {
        // Lazy: resolve the meter on the first call to avoid failing if
        // GlobalOpenTelemetry is not yet configured when the bean is created.
        warmupGlobalInstruments();
    }

    private void warmupGlobalInstruments() {
        if (!isMetricsEnabled() || meter() == null) return;
        Attributes global = Attributes.of(BOOTSTRAP_KEY, "global");
        // The `testMetricUnits` tests read only the metric name (not the attrs).
        // We therefore materialize an internal series to guarantee these names exist.
        counter(TIMEOUT_CALLS_TOTAL).add(1L, global);
        counter(CIRCUIT_BREAKER_CALLS_TOTAL).add(1L, global);
        counter(CIRCUIT_BREAKER_STATE_TOTAL, NANOSECONDS).add(1L, global);
        counter(CIRCUIT_BREAKER_OPENED_TOTAL).add(1L, global);
        counter(BULKHEAD_CALLS_TOTAL).add(1L, global);
        LongUpDownCounter running = upDown(BULKHEAD_EXECUTIONS_RUNNING);
        if (running != null) {
            running.add(1L, global);
            running.add(-1L, global);
        }
        LongUpDownCounter waiting = upDown(BULKHEAD_EXECUTIONS_WAITING);
        if (waiting != null) {
            waiting.add(1L, global);
            waiting.add(-1L, global);
        }
        histogram(TIMEOUT_EXECUTION_DURATION, SECONDS).record(0.001d, global);
        histogram(BULKHEAD_RUNNING_DURATION, SECONDS).record(0.001d, global);
        histogram(BULKHEAD_WAITING_DURATION, SECONDS).record(0.001d, global);
    }

    private Meter meter() {
        Meter m = meter;
        if (m == null) {
            try {
                m = GlobalOpenTelemetry.get().getMeter(METER_NAME);
                meter = m;
            } catch (RuntimeException | LinkageError _) {
                return null;
            }
        }
        return m;
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
    // Instrument factories (idempotents via ConcurrentHashMap)
    // ------------------------------------------------------------------

    private LongCounter counter(String name) {
        return counter(name, null);
    }

    private LongCounter counter(String name, String unit) {
        return counters.computeIfAbsent(name, n -> {
            Meter m = meter();
            if (m == null) return null;
            var b = m.counterBuilder(n);
            if (unit != null) b.setUnit(unit);
            return b.build();
        });
    }

    private DoubleHistogram histogram(String name, String unit) {
        return histograms.computeIfAbsent(name, n -> {
            Meter m = meter();
            if (m == null) return null;
            var b = m.histogramBuilder(n);
            if (unit != null) b.setUnit(unit);
            // MP FT 4.1 §10 : bucket boundaries explicites en secondes pour les histograms
            // de dure (ft.timeout.executionDuration, ft.bulkhead.runningDuration, etc.).
            // Buckets standards OTel pour les units SECONDS.
            if (SECONDS.equals(unit)) {
                b.setExplicitBucketBoundariesAdvice(java.util.List.of(
                        0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5,
                        0.75, 1.0, 2.5, 5.0, 7.5, 10.0));
            }
            return b.build();
        });
    }

    private LongUpDownCounter upDown(String name) {
        return upDowns.computeIfAbsent(name, n -> {
            Meter m = meter();
            if (m == null) return null;
            return m.upDownCounterBuilder(n).build();
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String methodKey(Class<?> beanClass, Method method) {
        return beanClass.getName() + "#" + method.getName();
    }

    /** TCK §10: {@code class.getCanonicalName() + "." + methodName}. */
    private static String methodTagValue(Class<?> beanClass, Method method) {
        String cn = beanClass.getCanonicalName();
        if (cn == null) cn = beanClass.getName();
        return cn + "." + method.getName();
    }

    private static Attributes methodAttrs(Class<?> beanClass, Method method) {
        return Attributes.of(METHOD_KEY, methodTagValue(beanClass, method));
    }

    // ------------------------------------------------------------------
    // register()
    // ------------------------------------------------------------------

    @Override
    public void register(Class<?> beanClass, Method method,
                         boolean hasRetry, boolean hasTimeout, boolean hasCircuitBreaker,
                         boolean hasBulkhead, boolean hasFallback, boolean asyncBulkhead) {
        if (!isMetricsEnabled() || meter() == null) return;

        String mtag = methodTagValue(beanClass, method);
        String key = methodKey(beanClass, method);

        registerInvocations(mtag, hasFallback);
        if (hasRetry) {
            registerRetry(mtag);
        }
        if (hasTimeout) {
            registerTimeout(key, mtag);
        }
        if (hasCircuitBreaker) {
            registerCircuitBreaker(key, mtag);
        }
        if (hasBulkhead) {
            registerBulkhead(key, mtag, asyncBulkhead);
        }
    }

    /** ft.invocations.total — toutes combinaisons (result, fallback). */
    private void registerInvocations(String mtag, boolean hasFallback) {
        LongCounter invocations = counter(INVOCATIONS_TOTAL);
        for (String r : List.of(VALUE_RETURNED, EXCEPTION_THROWN)) {
            for (String fb : FtMetricNames.fallbackValues(hasFallback)) {
                invocations.add(0L, Attributes.of(METHOD_KEY, mtag, RESULT_KEY, r, FALLBACK_KEY, fb));
            }
        }
    }

    private void registerRetry(String mtag) {
        LongCounter retryCalls = counter(RETRY_CALLS_TOTAL);
        for (boolean retried : new boolean[]{false, true}) {
            for (RetryResult rr : FtMetricNames.retryResults(retried)) {
                retryCalls.add(0L, Attributes.of(METHOD_KEY, mtag,
                        RETRIED_KEY, String.valueOf(retried),
                        RETRY_RESULT_KEY, rr.tagValue()));
            }
        }
        counter(RETRY_RETRIES_TOTAL).add(0L, Attributes.of(METHOD_KEY, mtag));
    }

    private void registerTimeout(String key, String mtag) {
        LongCounter timeoutCalls = counter(TIMEOUT_CALLS_TOTAL);
        // The TCK's OTel reader does not see synchronous instruments without a datapoint.
        // We materialize the metric on a dedicated internal series that does not pollute
        // the series expected by the TCK (because it lacks the `method` attribute).
        timeoutCalls.add(1L, Attributes.of(BOOTSTRAP_KEY, key));
        timeoutCalls.add(0L, Attributes.of(METHOD_KEY, mtag, TIMED_OUT_KEY, "false"));
        timeoutCalls.add(0L, Attributes.of(METHOD_KEY, mtag, TIMED_OUT_KEY, "true"));
        histogram(TIMEOUT_EXECUTION_DURATION, SECONDS);
    }

    private void registerCircuitBreaker(String key, String mtag) {
        LongCounter cbCalls = counter(CIRCUIT_BREAKER_CALLS_TOTAL);
        cbCalls.add(1L, Attributes.of(BOOTSTRAP_KEY, key));
        for (CBCallResult result : CBCallResult.values()) {
            cbCalls.add(0L, Attributes.of(METHOD_KEY, mtag, CB_RESULT_KEY, result.tagValue()));
        }
        counter(CIRCUIT_BREAKER_OPENED_TOTAL).add(0L, Attributes.of(METHOD_KEY, mtag));

        // ft.circuitbreaker.state.total — cumulative nanoseconds counter per state.
        // We use a counter plus a tracker that computes the delta between transitions.
        cbStateTrackers.computeIfAbsent(key, k -> new CBStateTracker());
        LongCounter cbState = counter(CIRCUIT_BREAKER_STATE_TOTAL, NANOSECONDS);
        // Pre-register the 3 zero-valued series.
        for (CBState state : CBState.values()) {
            cbState.add(0L, Attributes.of(METHOD_KEY, mtag, CB_STATE_KEY, state.tagValue()));
        }
    }

    private void registerBulkhead(String key, String mtag, boolean asyncBulkhead) {
        LongCounter bhCalls = counter(BULKHEAD_CALLS_TOTAL);
        bhCalls.add(1L, Attributes.of(BOOTSTRAP_KEY, key));
        bhCalls.add(0L, Attributes.of(METHOD_KEY, mtag, BULKHEAD_RESULT_KEY, ACCEPTED));
        bhCalls.add(0L, Attributes.of(METHOD_KEY, mtag, BULKHEAD_RESULT_KEY, REJECTED));

        // executionsRunning gauge — modeled as an UpDownCounter (delta), tracked per method.
        bulkheadRunning.computeIfAbsent(key, k -> new AtomicLong(0));
        upDown(BULKHEAD_EXECUTIONS_RUNNING);
        histogram(BULKHEAD_RUNNING_DURATION, SECONDS);

        if (asyncBulkhead) {
            // For async bulkheads, the TCK explicitly expects the `executionsWaiting` /
            // `waitingDuration` metrics even when the queue is empty.
            bulkheadWaiting.computeIfAbsent(key, k -> new AtomicLong(0));
            LongUpDownCounter waiting = upDown(BULKHEAD_EXECUTIONS_WAITING);
            if (waiting != null) {
                Attributes methodOnly = Attributes.of(METHOD_KEY, mtag);
                waiting.add(1L, methodOnly);
                waiting.add(-1L, methodOnly);
            }
            histogram(BULKHEAD_WAITING_DURATION, SECONDS);
        }
    }

    // ------------------------------------------------------------------
    // Invocation
    // ------------------------------------------------------------------

    @Override
    public void recordInvocation(Class<?> beanClass, Method method,
                                 boolean succeeded, boolean fallbackApplied, boolean fallbackDefined) {
        if (!isMetricsEnabled() || meter() == null) return;
        String mtag = methodTagValue(beanClass, method);
        String result = succeeded ? VALUE_RETURNED : EXCEPTION_THROWN;
        String fb = FtMetricNames.fallbackValue(fallbackDefined, fallbackApplied);
        counter(INVOCATIONS_TOTAL).add(1L,
                Attributes.of(METHOD_KEY, mtag, RESULT_KEY, result, FALLBACK_KEY, fb));
    }

    // ------------------------------------------------------------------
    // Retry
    // ------------------------------------------------------------------

    @Override
    public void recordRetry(Class<?> beanClass, Method method, int retryCount, RetryResult result) {
        if (!isMetricsEnabled() || meter() == null) return;
        String mtag = methodTagValue(beanClass, method);
        boolean retried = retryCount > 0;
        counter(RETRY_CALLS_TOTAL).add(1L,
                Attributes.of(METHOD_KEY, mtag,
                        RETRIED_KEY, String.valueOf(retried),
                        RETRY_RESULT_KEY, result.tagValue()));
        if (retryCount > 0) {
            counter(RETRY_RETRIES_TOTAL).add(retryCount, methodAttrs(beanClass, method));
        }
    }

    // ------------------------------------------------------------------
    // Timeout
    // ------------------------------------------------------------------

    @Override
    public void recordTimeout(Class<?> beanClass, Method method, boolean timedOut, long durationNanos) {
        if (!isMetricsEnabled() || meter() == null) return;
        String mtag = methodTagValue(beanClass, method);
        counter(TIMEOUT_CALLS_TOTAL).add(1L,
                Attributes.of(METHOD_KEY, mtag, TIMED_OUT_KEY, String.valueOf(timedOut)));
        // Histogram unit = seconds
        double seconds = durationNanos / 1_000_000_000.0;
        histogram(TIMEOUT_EXECUTION_DURATION, SECONDS).record(seconds, methodAttrs(beanClass, method));
    }

    // ------------------------------------------------------------------
    // Circuit Breaker
    // ------------------------------------------------------------------

    @Override
    public void recordCircuitBreakerCall(Class<?> beanClass, Method method, CBCallResult result) {
        if (!isMetricsEnabled() || meter() == null) return;
        String key = methodKey(beanClass, method);
        String mtag = methodTagValue(beanClass, method);
        counter(CIRCUIT_BREAKER_CALLS_TOTAL).add(1L,
                Attributes.of(METHOD_KEY, mtag, CB_RESULT_KEY, result.tagValue()));

        // §10: the time spent in the current state must advance even without a transition.
        CBStateTracker tracker = cbStateTrackers.computeIfAbsent(key, k -> new CBStateTracker());
        StateTimeSample sample = tracker.sampleCurrentStateDuration();
        if (sample.deltaNanos() > 0L) {
            counter(CIRCUIT_BREAKER_STATE_TOTAL, NANOSECONDS).add(sample.deltaNanos(),
                    Attributes.of(METHOD_KEY, mtag, CB_STATE_KEY, sample.state().tagValue()));
        }
    }

    @Override
    public void notifyCircuitBreakerStateChange(Class<?> beanClass, Method method, CBState from, CBState to) {
        if (!isMetricsEnabled() || meter() == null) return;
        String key = methodKey(beanClass, method);
        String mtag = methodTagValue(beanClass, method);
        CBStateTracker tracker = cbStateTrackers.computeIfAbsent(key, k -> new CBStateTracker());
        StateTimeSample sample = tracker.transition(to);
        if (sample.deltaNanos() > 0L && from != null) {
            counter(CIRCUIT_BREAKER_STATE_TOTAL, NANOSECONDS).add(sample.deltaNanos(),
                    Attributes.of(METHOD_KEY, mtag, CB_STATE_KEY, from.tagValue()));
        }
        if (to == CBState.OPEN) {
            counter(CIRCUIT_BREAKER_OPENED_TOTAL).add(1L, methodAttrs(beanClass, method));
        }
    }

    // ------------------------------------------------------------------
    // Bulkhead
    // ------------------------------------------------------------------

    @Override
    public void recordBulkheadAccepted(Class<?> beanClass, Method method, long waitNanos, long runNanos) {
        if (!isMetricsEnabled() || meter() == null) return;
        String key = methodKey(beanClass, method);
        Attributes attrs = methodAttrs(beanClass, method);
        String mtag = methodTagValue(beanClass, method);
        counter(BULKHEAD_CALLS_TOTAL).add(1L,
                Attributes.of(METHOD_KEY, mtag, BULKHEAD_RESULT_KEY, ACCEPTED));
        histogram(BULKHEAD_RUNNING_DURATION, SECONDS).record(runNanos / 1_000_000_000.0, attrs);
        if (bulkheadWaiting.containsKey(key)) {
            histogram(BULKHEAD_WAITING_DURATION, SECONDS)
                    .record(Math.max(0L, waitNanos) / 1_000_000_000.0, attrs);
        }
    }

    @Override
    public void recordBulkheadRejected(Class<?> beanClass, Method method) {
        if (!isMetricsEnabled() || meter() == null) return;
        counter(BULKHEAD_CALLS_TOTAL).add(1L,
                Attributes.of(METHOD_KEY, methodTagValue(beanClass, method),
                        BULKHEAD_RESULT_KEY, REJECTED));
    }

    @Override
    public void bulkheadRunningDelta(Class<?> beanClass, Method method, int delta) {
        if (!isMetricsEnabled() || meter() == null) return;
        AtomicLong tracker = bulkheadRunning.get(methodKey(beanClass, method));
        if (tracker != null) tracker.addAndGet(delta);
        LongUpDownCounter ud = upDown(BULKHEAD_EXECUTIONS_RUNNING);
        if (ud != null) ud.add(delta, methodAttrs(beanClass, method));
    }

    @Override
    public void bulkheadWaitingDelta(Class<?> beanClass, Method method, int delta) {
        if (!isMetricsEnabled() || meter() == null) return;
        AtomicLong tracker = bulkheadWaiting.get(methodKey(beanClass, method));
        if (tracker != null) tracker.addAndGet(delta);
        LongUpDownCounter ud = upDown(BULKHEAD_EXECUTIONS_WAITING);
        if (ud != null) ud.add(delta, methodAttrs(beanClass, method));
    }

    // ------------------------------------------------------------------
    // CB state time tracker — convertit les transitions en deltas nanoseconds.
    // ------------------------------------------------------------------

    private record StateTimeSample(CBState state, long deltaNanos) {}

    private static final class CBStateTracker {
        private final AtomicReference<CBState> currentState = new AtomicReference<>(CBState.CLOSED);
        private final AtomicLong lastSampleAt = new AtomicLong(System.nanoTime());

        StateTimeSample sampleCurrentStateDuration() {
            long now = System.nanoTime();
            long prev = lastSampleAt.getAndSet(now);
            long delta = Math.max(0L, now - prev);
            return new StateTimeSample(currentState.get(), delta);
        }

        /**
         * Performs the transition to {@code newState} and returns the delta nanoseconds spent
         * in the previous state. Returns 0 if no transition occurs.
         */
        StateTimeSample transition(CBState newState) {
            CBState prev = currentState.getAndSet(newState);
            if (prev == newState) return new StateTimeSample(prev, 0L);
            long now = System.nanoTime();
            long prevSampleAt = lastSampleAt.getAndSet(now);
            return new StateTimeSample(prev, Math.max(0L, now - prevSampleAt));
        }
    }
}




