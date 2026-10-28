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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.vidocq.heisenberg.api.FtMetricsRecorder.CBCallResult;
import io.vidocq.heisenberg.api.FtMetricsRecorder.CBState;
import io.vidocq.heisenberg.api.FtMetricsRecorder.RetryResult;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * MicroProfile Fault Tolerance 4.1 §10: the OpenTelemetry metrics, read back from a recording
 * OpenTelemetry installed as the global one.
 */
class OtelFtMetricsTest {

    /** An instance of an anonymous class, whose canonical name is null. */
    private static final Object ANONYMOUS_BEAN = new Object() { };

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
    private static final String BOOTSTRAP = "heisenberg.bootstrap";
    private static final String SECONDS = "seconds";
    private static final String NANOSECONDS = "nanoseconds";
    private static final String GLOBAL = "global";
    private static final String FALLBACK = "fallback";
    private static final String STATE = "state";
    private static final String NOT_DEFINED = "notDefined";
    private static final String FALSE = "false";
    private static final String TAG = Service.class.getCanonicalName() + ".call";

    static class Service {
        void call() {
            // the guarded method the metrics are named after
        }
    }

    private RecordingOpenTelemetry recording;
    private Method call;

    @BeforeEach
    void installRecordingOpenTelemetry() throws NoSuchMethodException {
        GlobalOpenTelemetry.resetForTest();
        recording = new RecordingOpenTelemetry();
        GlobalOpenTelemetry.set(recording.openTelemetry());
        call = Service.class.getDeclaredMethod("call");
    }

    @AfterEach
    void resetGlobalOpenTelemetry() {
        GlobalOpenTelemetry.resetForTest();
    }

    /** The points of {@code instrument} for the guarded method, without the bootstrap series. */
    private List<RecordingOpenTelemetry.Point> methodPoints(String instrument) {
        return recording.points(instrument, METHOD, TAG);
    }

    private double methodSum(String instrument, String key, String value) {
        return methodPoints(instrument).stream()
                .filter(p -> value.equals(p.attribute(key)))
                .mapToDouble(RecordingOpenTelemetry.Point::value)
                .sum();
    }

    @Test
    void creationMaterializesEveryInstrumentOnABootstrapSeries() {
        new OtelFtMetrics();

        assertEquals(List.of("io.vidocq.heisenberg"), recording.meterNames().stream().distinct().toList());
        for (String counter : List.of(TIMEOUT_CALLS, CB_CALLS, CB_STATE, CB_OPENED, BULKHEAD_CALLS)) {
            assertEquals(1.0, recording.sum(counter, BOOTSTRAP, GLOBAL), counter);
        }
        assertEquals(0.0, recording.sum(RUNNING, BOOTSTRAP, GLOBAL));
        assertEquals(2, recording.points(WAITING, BOOTSTRAP, GLOBAL).size());
        assertEquals(NANOSECONDS, recording.unit(CB_STATE));
        for (String histogram : List.of(TIMEOUT_DURATION, RUNNING_DURATION, WAITING_DURATION)) {
            assertEquals(SECONDS, recording.unit(histogram), histogram);
            assertEquals(14, recording.bucketAdvice(histogram).size(), histogram);
            assertEquals(0.001, recording.sum(histogram, BOOTSTRAP, GLOBAL), 1e-9);
        }
    }

    @Test
    void registerPublishesZeroSeriesForEveryPolicy() {
        OtelFtMetrics metrics = new OtelFtMetrics();

        metrics.register(Service.class, call, true, true, true, true, true, true);

        // §10: every (result, fallback) pair exists from the start, with value 0.
        assertEquals(4, methodPoints(INVOCATIONS).size());
        assertEquals(0.0, methodSum(INVOCATIONS, FALLBACK, "applied"));
        assertEquals(2, methodPoints(INVOCATIONS).stream().filter(p -> "notApplied".equals(p.attribute(FALLBACK))).count());
        assertEquals(6, methodPoints(RETRY_CALLS).size());
        assertEquals(4, methodPoints(RETRY_CALLS).stream().filter(p -> "true".equals(p.attribute("retried"))).count());
        assertEquals(1, methodPoints(RETRIES).size());
        assertEquals(2, methodPoints(TIMEOUT_CALLS).size());
        assertEquals(3, methodPoints(CB_CALLS).size());
        assertEquals(1, methodPoints(CB_OPENED).size());
        assertEquals(List.of("closed", "open", "halfOpen"),
                methodPoints(CB_STATE).stream().map(p -> p.attribute(STATE)).toList());
        assertEquals(2, methodPoints(BULKHEAD_CALLS).size());
        // Async bulkhead: the waiting series is materialized by +1 then -1.
        assertEquals(List.of(1.0, -1.0), methodPoints(WAITING).stream().map(RecordingOpenTelemetry.Point::value).toList());
        String key = Service.class.getName() + "#call";
        assertEquals(1.0, recording.sum(TIMEOUT_CALLS, BOOTSTRAP, key));
        assertEquals(1.0, recording.sum(CB_CALLS, BOOTSTRAP, key));
        assertEquals(1.0, recording.sum(BULKHEAD_CALLS, BOOTSTRAP, key));
        assertTrue(methodPoints(INVOCATIONS).stream().allMatch(p -> p.value() == 0.0));
    }

    @Test
    void registerWithoutFallbackOrOtherPoliciesOnlyPublishesInvocations() {
        OtelFtMetrics metrics = new OtelFtMetrics();

        metrics.register(Service.class, call, false, false, false, false, false, false);

        assertEquals(List.of(NOT_DEFINED, NOT_DEFINED),
                methodPoints(INVOCATIONS).stream().map(p -> p.attribute(FALLBACK)).toList());
        assertEquals(List.of(), methodPoints(RETRY_CALLS));
        assertEquals(List.of(), methodPoints(TIMEOUT_CALLS));
        assertEquals(List.of(), methodPoints(CB_CALLS));
        assertEquals(List.of(), methodPoints(BULKHEAD_CALLS));
    }

    @Test
    void recordInvocationCountsResultAndFallback() {
        OtelFtMetrics metrics = new OtelFtMetrics();

        metrics.recordInvocation(Service.class, call, true, false, false);
        metrics.recordInvocation(Service.class, call, false, true, true);
        metrics.recordInvocation(Service.class, call, false, false, true);

        List<RecordingOpenTelemetry.Point> points = methodPoints(INVOCATIONS);
        assertEquals(List.of("valueReturned", "exceptionThrown", "exceptionThrown"),
                points.stream().map(p -> p.attribute("result")).toList());
        assertEquals(List.of(NOT_DEFINED, "applied", "notApplied"),
                points.stream().map(p -> p.attribute(FALLBACK)).toList());
        assertEquals(3.0, points.stream().mapToDouble(RecordingOpenTelemetry.Point::value).sum());
    }

    @Test
    void recordRetryCountsCallsAndRetries() {
        OtelFtMetrics metrics = new OtelFtMetrics();

        metrics.recordRetry(Service.class, call, 0, RetryResult.VALUE_RETURNED);
        metrics.recordRetry(Service.class, call, 3, RetryResult.MAX_RETRIES_REACHED);

        assertEquals(1.0, methodSum(RETRY_CALLS, "retried", FALSE));
        assertEquals(1.0, methodSum(RETRY_CALLS, "retryResult", "maxRetriesReached"));
        assertEquals(List.of(3.0), methodPoints(RETRIES).stream().map(RecordingOpenTelemetry.Point::value).toList());
    }

    @Test
    void recordTimeoutCountsCallsAndRecordsTheDurationInSeconds() {
        OtelFtMetrics metrics = new OtelFtMetrics();

        metrics.recordTimeout(Service.class, call, true, 1_500_000_000L);
        metrics.recordTimeout(Service.class, call, false, 0L);

        assertEquals(1.0, methodSum(TIMEOUT_CALLS, "timedOut", "true"));
        assertEquals(1.0, methodSum(TIMEOUT_CALLS, "timedOut", FALSE));
        assertEquals(List.of(1.5, 0.0),
                methodPoints(TIMEOUT_DURATION).stream().map(RecordingOpenTelemetry.Point::value).toList());
    }

    @Test
    void circuitBreakerCallsAndTransitionsAccumulateTimePerState() {
        OtelFtMetrics metrics = new OtelFtMetrics();
        metrics.register(Service.class, call, false, false, true, false, false, false);

        waitForTheClockToMove();
        metrics.recordCircuitBreakerCall(Service.class, call, CBCallResult.FAILURE);
        waitForTheClockToMove();
        metrics.notifyCircuitBreakerStateChange(Service.class, call, CBState.CLOSED, CBState.OPEN);
        waitForTheClockToMove();
        metrics.recordCircuitBreakerCall(Service.class, call, CBCallResult.CIRCUIT_BREAKER_OPEN);
        // Without a known previous state, the elapsed time is not attributed.
        waitForTheClockToMove();
        metrics.notifyCircuitBreakerStateChange(Service.class, call, null, CBState.HALF_OPEN);

        assertEquals(1.0, methodSum(CB_CALLS, "circuitBreakerResult", "failure"));
        assertEquals(1.0, methodSum(CB_CALLS, "circuitBreakerResult", "circuitBreakerOpen"));
        assertEquals(1.0, methodPoints(CB_OPENED).stream().mapToDouble(RecordingOpenTelemetry.Point::value).sum());
        assertTrue(methodSum(CB_STATE, STATE, "closed") > 0.0);
        assertTrue(methodSum(CB_STATE, STATE, "open") > 0.0);
        assertEquals(0.0, methodSum(CB_STATE, STATE, "halfOpen"));
    }

    @Test
    void circuitBreakerStateChangeWithoutRegistrationStillCountsTheOpening() {
        OtelFtMetrics metrics = new OtelFtMetrics();

        metrics.notifyCircuitBreakerStateChange(Service.class, call, CBState.CLOSED, CBState.OPEN);

        assertEquals(1, methodPoints(CB_OPENED).size());
    }

    @Test
    void bulkheadCallsDurationsAndDeltas() {
        OtelFtMetrics metrics = new OtelFtMetrics();
        metrics.register(Service.class, call, false, false, false, true, false, true);

        metrics.bulkheadWaitingDelta(Service.class, call, 1);
        metrics.bulkheadWaitingDelta(Service.class, call, -1);
        metrics.bulkheadRunningDelta(Service.class, call, 1);
        metrics.recordBulkheadAccepted(Service.class, call, -5L, 2_000_000_000L);
        metrics.bulkheadRunningDelta(Service.class, call, -1);
        metrics.recordBulkheadRejected(Service.class, call);

        assertEquals(1.0, methodSum(BULKHEAD_CALLS, "bulkheadResult", "accepted"));
        assertEquals(1.0, methodSum(BULKHEAD_CALLS, "bulkheadResult", "rejected"));
        assertEquals(List.of(2.0), methodPoints(RUNNING_DURATION).stream().map(RecordingOpenTelemetry.Point::value).toList());
        // A negative wait is reported as zero.
        assertEquals(List.of(0.0), methodPoints(WAITING_DURATION).stream().map(RecordingOpenTelemetry.Point::value).toList());
        assertEquals(List.of(1.0, -1.0), methodPoints(RUNNING).stream().map(RecordingOpenTelemetry.Point::value).toList());
        assertEquals(List.of(1.0, -1.0, 1.0, -1.0),
                methodPoints(WAITING).stream().map(RecordingOpenTelemetry.Point::value).toList());
    }

    @Test
    void synchronousBulkheadReportsNoWaitingDuration() {
        OtelFtMetrics metrics = new OtelFtMetrics();
        metrics.register(Service.class, call, false, false, false, true, false, false);

        metrics.recordBulkheadAccepted(Service.class, call, 10L, 20L);
        metrics.bulkheadWaitingDelta(Service.class, call, 1);

        assertEquals(List.of(), methodPoints(WAITING_DURATION));
        assertEquals(1, methodPoints(RUNNING_DURATION).size());
        assertEquals(List.of(1.0), methodPoints(WAITING).stream().map(RecordingOpenTelemetry.Point::value).toList());
    }

    @Test
    void anonymousBeanClassIsNamedAfterItsBinaryName() {
        OtelFtMetrics metrics = new OtelFtMetrics();
        Class<?> anonymous = ANONYMOUS_BEAN.getClass();

        metrics.recordBulkheadRejected(anonymous, call);

        assertEquals(1, recording.points(BULKHEAD_CALLS, METHOD, anonymous.getName() + ".call").size());
    }

    @Test
    void disabledMetricsPublishNothing() throws Exception {
        try (var _ = TestConfigs.install(Map.of(TestConfigs.METRICS_ENABLED, FALSE))) {
            OtelFtMetrics metrics = new OtelFtMetrics();

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

        assertEquals(List.of(), recording.meterNames());
        assertEquals(List.of(), recording.points(INVOCATIONS));
        assertEquals(List.of(), recording.points(BULKHEAD_CALLS));
    }

    /** Busy-waits until {@link System#nanoTime()} has advanced, so that elapsed times are positive. */
    private static void waitForTheClockToMove() {
        long start = System.nanoTime();
        while (System.nanoTime() == start) {
            Thread.onSpinWait();
        }
    }
}
