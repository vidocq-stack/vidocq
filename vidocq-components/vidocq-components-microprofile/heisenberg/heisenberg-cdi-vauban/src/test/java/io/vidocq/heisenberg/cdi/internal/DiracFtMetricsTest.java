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

import static io.vidocq.heisenberg.cdi.internal.RecordingMetricRegistry.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.heisenberg.api.FtMetricsRecorder;
import io.vidocq.heisenberg.api.FtMetricsRecorder.CBCallResult;
import io.vidocq.heisenberg.api.FtMetricsRecorder.CBState;
import io.vidocq.heisenberg.api.FtMetricsRecorder.RetryResult;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.metrics.MetricID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** MicroProfile Fault Tolerance 4.1 §9: the MicroProfile Metrics published in the base scope. */
class DiracFtMetricsTest {

    private static final String INVOCATIONS = "ft.invocations.total";
    private static final String RETRY_CALLS = "ft.retry.calls.total";
    private static final String RETRIES = "ft.retry.retries.total";
    private static final String TIMEOUT_CALLS = "ft.timeout.calls.total";
    private static final String TIMEOUT_DURATION = "ft.timeout.executionDuration";
    private static final String CB_CALLS = "ft.circuitbreaker.calls.total";
    private static final String CB_STATE = "ft.circuitbreaker.state.total";
    private static final String CB_OPENED = "ft.circuitbreaker.opened.total";
    private static final String BULKHEAD_CALLS = "ft.bulkhead.calls.total";
    private static final String RUNNING = "ft.bulkhead.executionsRunning";
    private static final String WAITING = "ft.bulkhead.executionsWaiting";
    private static final String RUNNING_DURATION = "ft.bulkhead.runningDuration";
    private static final String WAITING_DURATION = "ft.bulkhead.waitingDuration";

    private static final String METHOD = "method";
    private static final String RESULT = "result";
    private static final String FALLBACK = "fallback";
    private static final String STATE = "state";
    private static final String BULKHEAD_RESULT = "bulkheadResult";
    private static final String CB_RESULT = "circuitBreakerResult";
    private static final String VALUE_RETURNED = "valueReturned";
    private static final String EXCEPTION_THROWN = "exceptionThrown";
    private static final String NANOSECONDS = "nanoseconds";
    private static final String RETRIED = "retried";
    private static final String RETRY_RESULT = "retryResult";
    private static final String FALSE = "false";
    private static final String TIMED_OUT = "timedOut";
    private static final String TAG = Service.class.getName() + ".call";

    static class Service {
        void call() {
            // the guarded method the metrics are named after
        }
    }

    private RecordingMetricRegistry recording;
    private DiracFtMetrics metrics;
    private Method call;

    @BeforeEach
    void createMetrics() throws NoSuchMethodException {
        recording = new RecordingMetricRegistry();
        metrics = new DiracFtMetrics(recording.registry);
        call = Service.class.getDeclaredMethod("call");
    }

    private static MetricID metric(String name, String... tags) {
        String[] all = new String[tags.length + 2];
        all[0] = METHOD;
        all[1] = TAG;
        System.arraycopy(tags, 0, all, 2, tags.length);
        return id(name, all);
    }

    @Test
    void createUsesTheResolvableRegistry() {
        FtMetricsRecorder created = DiracFtMetrics.create(new FixedInstance(recording.registry).instance());

        assertInstanceOf(DiracFtMetrics.class, created);
    }

    @Test
    void createWithoutARegistryBeanIsANoop() {
        assertSame(FtMetricsRecorder.NOOP, DiracFtMetrics.create(new FixedInstance(null).instance()));
    }

    @Test
    void registerCreatesEveryMetricWithValueZero() {
        metrics.register(Service.class, call, true, true, true, true, true, true);

        for (String result : List.of(VALUE_RETURNED, EXCEPTION_THROWN)) {
            for (String fallback : List.of("applied", "notApplied")) {
                assertEquals(0, recording.count(metric(INVOCATIONS, RESULT, result, FALLBACK, fallback)));
            }
        }
        assertEquals(0, recording.count(metric(RETRY_CALLS, RETRIED, "true", RETRY_RESULT, "maxDurationReached")));
        assertEquals(0, recording.count(metric(RETRY_CALLS, RETRIED, FALSE, RETRY_RESULT, "exceptionNotRetryable")));
        assertFalse(recording.hasCounter(metric(RETRY_CALLS, RETRIED, FALSE, RETRY_RESULT, "maxRetriesReached")));
        assertEquals(0, recording.count(metric(RETRIES)));
        assertEquals(0, recording.count(metric(TIMEOUT_CALLS, TIMED_OUT, "true")));
        assertEquals(List.of(), recording.histogramValues(metric(TIMEOUT_DURATION)));
        assertEquals(NANOSECONDS, recording.unit(TIMEOUT_DURATION));
        assertEquals(0, recording.count(metric(CB_CALLS, CB_RESULT, "circuitBreakerOpen")));
        assertEquals(0, recording.count(metric(CB_OPENED)));
        assertEquals(0L, recording.gaugeValue(metric(CB_STATE, STATE, "open")));
        assertEquals(0L, recording.gaugeValue(metric(CB_STATE, STATE, "halfOpen")));
        assertEquals(NANOSECONDS, recording.unit(CB_STATE));
        assertEquals(0, recording.count(metric(BULKHEAD_CALLS, BULKHEAD_RESULT, "rejected")));
        assertEquals(0L, recording.gaugeValue(metric(RUNNING)));
        assertEquals(0L, recording.gaugeValue(metric(WAITING)));
        assertEquals(List.of(), recording.histogramValues(metric(RUNNING_DURATION)));
        assertEquals(List.of(), recording.histogramValues(metric(WAITING_DURATION)));
    }

    @Test
    void registerIsIdempotentAndHonoursTheAbsentPolicies() {
        metrics.register(Service.class, call, false, false, false, false, false, false);
        long afterFirst = recording.counterCount();
        metrics.register(Service.class, call, true, true, true, true, true, true);

        assertEquals(2, afterFirst);
        assertEquals(afterFirst, recording.counterCount());
        assertEquals(0, recording.count(metric(INVOCATIONS, RESULT, VALUE_RETURNED, FALLBACK, "notDefined")));
        assertFalse(recording.hasCounter(metric(RETRIES)));
    }

    @Test
    void recordInvocationCountsResultAndFallback() {
        metrics.recordInvocation(Service.class, call, true, false, false);
        metrics.recordInvocation(Service.class, call, false, true, true);
        metrics.recordInvocation(Service.class, call, false, false, true);
        metrics.recordInvocation(Service.class, call, false, false, true);

        assertEquals(1, recording.count(metric(INVOCATIONS, RESULT, VALUE_RETURNED, FALLBACK, "notDefined")));
        assertEquals(1, recording.count(metric(INVOCATIONS, RESULT, EXCEPTION_THROWN, FALLBACK, "applied")));
        assertEquals(2, recording.count(metric(INVOCATIONS, RESULT, EXCEPTION_THROWN, FALLBACK, "notApplied")));
    }

    @Test
    void recordRetryCountsCallsAndRetries() {
        metrics.recordRetry(Service.class, call, 0, RetryResult.EXCEPTION_NOT_RETRYABLE);
        metrics.recordRetry(Service.class, call, 2, RetryResult.MAX_DURATION_REACHED);
        metrics.recordRetry(Service.class, call, 1, RetryResult.VALUE_RETURNED);

        assertEquals(1, recording.count(metric(RETRY_CALLS, RETRIED, FALSE, RETRY_RESULT, "exceptionNotRetryable")));
        assertEquals(1, recording.count(metric(RETRY_CALLS, RETRIED, "true", RETRY_RESULT, "maxDurationReached")));
        assertEquals(1, recording.count(metric(RETRY_CALLS, RETRIED, "true", RETRY_RESULT, VALUE_RETURNED)));
        assertEquals(3, recording.count(metric(RETRIES)));
    }

    @Test
    void recordTimeoutCountsCallsAndRecordsTheDuration() {
        metrics.recordTimeout(Service.class, call, true, 42L);
        metrics.recordTimeout(Service.class, call, false, 7L);

        assertEquals(1, recording.count(metric(TIMEOUT_CALLS, TIMED_OUT, "true")));
        assertEquals(1, recording.count(metric(TIMEOUT_CALLS, TIMED_OUT, FALSE)));
        assertEquals(List.of(42L, 7L), recording.histogramValues(metric(TIMEOUT_DURATION)));
    }

    @Test
    void circuitBreakerCallsOpeningsAndTimePerState() {
        metrics.register(Service.class, call, false, false, true, false, false, false);

        metrics.recordCircuitBreakerCall(Service.class, call, CBCallResult.SUCCESS);
        metrics.recordCircuitBreakerCall(Service.class, call, CBCallResult.FAILURE);
        waitForTheClockToMove();
        metrics.notifyCircuitBreakerStateChange(Service.class, call, CBState.CLOSED, CBState.OPEN);
        waitForTheClockToMove();
        metrics.notifyCircuitBreakerStateChange(Service.class, call, CBState.OPEN, CBState.HALF_OPEN);
        // A notification for the state already held changes nothing in the tracker.
        metrics.notifyCircuitBreakerStateChange(Service.class, call, CBState.HALF_OPEN, CBState.HALF_OPEN);
        waitForTheClockToMove();

        assertEquals(1, recording.count(metric(CB_CALLS, CB_RESULT, "success")));
        assertEquals(1, recording.count(metric(CB_CALLS, CB_RESULT, "failure")));
        assertEquals(1, recording.count(metric(CB_OPENED)));
        long closed = recording.gaugeValue(metric(CB_STATE, STATE, "closed")).longValue();
        long open = recording.gaugeValue(metric(CB_STATE, STATE, "open")).longValue();
        assertTrue(closed > 0, "time spent closed");
        assertTrue(open > 0, "time spent open");
        assertTrue(recording.gaugeValue(metric(CB_STATE, STATE, "halfOpen")).longValue() > 0, "time spent half-open");
        // Closed and open are over: their accumulated time no longer moves.
        waitForTheClockToMove();
        assertEquals(closed, recording.gaugeValue(metric(CB_STATE, STATE, "closed")).longValue());
        assertEquals(open, recording.gaugeValue(metric(CB_STATE, STATE, "open")).longValue());
    }

    @Test
    void circuitBreakerOpeningWithoutRegistrationIsStillCounted() {
        metrics.notifyCircuitBreakerStateChange(Service.class, call, CBState.CLOSED, CBState.OPEN);
        metrics.notifyCircuitBreakerStateChange(Service.class, call, CBState.OPEN, CBState.HALF_OPEN);

        assertEquals(1, recording.count(metric(CB_OPENED)));
        assertNull(recording.gaugeValue(metric(CB_STATE, STATE, "open")));
    }

    @Test
    void asynchronousBulkheadTracksRunningWaitingAndDurations() {
        metrics.register(Service.class, call, false, false, false, true, false, true);

        metrics.bulkheadWaitingDelta(Service.class, call, 2);
        metrics.bulkheadWaitingDelta(Service.class, call, -1);
        metrics.bulkheadRunningDelta(Service.class, call, 1);
        metrics.recordBulkheadAccepted(Service.class, call, -3L, 100L);
        metrics.recordBulkheadAccepted(Service.class, call, 50L, 200L);
        metrics.recordBulkheadRejected(Service.class, call);

        assertEquals(1L, recording.gaugeValue(metric(RUNNING)));
        assertEquals(1L, recording.gaugeValue(metric(WAITING)));
        assertEquals(2, recording.count(metric(BULKHEAD_CALLS, BULKHEAD_RESULT, "accepted")));
        assertEquals(1, recording.count(metric(BULKHEAD_CALLS, BULKHEAD_RESULT, "rejected")));
        assertEquals(List.of(100L, 200L), recording.histogramValues(metric(RUNNING_DURATION)));
        // A negative wait is reported as zero.
        assertEquals(List.of(0L, 50L), recording.histogramValues(metric(WAITING_DURATION)));
    }

    @Test
    void synchronousBulkheadReportsNoWaitingMetrics() {
        metrics.register(Service.class, call, false, false, false, true, false, false);

        metrics.bulkheadWaitingDelta(Service.class, call, 1);
        metrics.recordBulkheadAccepted(Service.class, call, 10L, 20L);

        assertNull(recording.gaugeValue(metric(WAITING)));
        assertNull(recording.histogramValues(metric(WAITING_DURATION)));
        assertEquals(List.of(20L), recording.histogramValues(metric(RUNNING_DURATION)));
    }

    @Test
    void deltasForAnUnregisteredBulkheadAreIgnored() {
        metrics.bulkheadRunningDelta(Service.class, call, 1);
        metrics.bulkheadWaitingDelta(Service.class, call, 1);

        assertNull(recording.gaugeValue(metric(RUNNING)));
        assertEquals(0, recording.counterCount());
    }

    @Test
    void disabledMetricsPublishNothing() throws Exception {
        try (AutoCloseable _ = TestConfigs.install(Map.of(TestConfigs.METRICS_ENABLED, FALSE))) {
            metrics.register(Service.class, call, true, true, true, true, true, true);
            metrics.recordInvocation(Service.class, call, true, false, false);
            metrics.recordRetry(Service.class, call, 1, RetryResult.VALUE_RETURNED);
            metrics.recordTimeout(Service.class, call, false, 1L);
            metrics.recordCircuitBreakerCall(Service.class, call, CBCallResult.SUCCESS);
            metrics.notifyCircuitBreakerStateChange(Service.class, call, CBState.CLOSED, CBState.OPEN);
            metrics.recordBulkheadAccepted(Service.class, call, 1L, 1L);
            metrics.recordBulkheadRejected(Service.class, call);
            metrics.bulkheadRunningDelta(Service.class, call, 1);
            metrics.bulkheadWaitingDelta(Service.class, call, 1);
        }

        assertEquals(0, recording.counterCount());
        assertNull(recording.histogramValues(metric(TIMEOUT_DURATION)));
    }

    /** Busy-waits until {@link System#nanoTime()} has advanced, so that elapsed times are positive. */
    private static void waitForTheClockToMove() {
        long start = System.nanoTime();
        while (System.nanoTime() == start) {
            Thread.onSpinWait();
        }
    }
}
