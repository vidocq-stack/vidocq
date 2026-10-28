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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.vidocq.heisenberg.api.FtMetricsRecorder;
import io.vidocq.heisenberg.api.FtMetricsRecorder.CBCallResult;
import io.vidocq.heisenberg.api.FtMetricsRecorder.CBState;
import io.vidocq.heisenberg.api.FtMetricsRecorder.RetryResult;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The metrics recorder beans and their plumbing: delegation to the API-specific recorder, fan-out
 * to several recorders, and the no-op recorder.
 */
class MetricsRecordersTest {

    /** Every {@link FtMetricsRecorder} method, in the order {@link #callEveryMethod} calls them. */
    private static final List<String> ALL_CALLS = List.of(
            "register", "recordInvocation", "recordRetry", "recordTimeout", "recordCircuitBreakerCall",
            "notifyCircuitBreakerStateChange", "recordBulkheadAccepted", "recordBulkheadRejected",
            "bulkheadRunningDelta", "bulkheadWaitingDelta");

    static class Service {
        void call() {
            // the guarded method the metrics are named after
        }
    }

    /** A recorder that lists the methods called on it, and optionally fails each of them. */
    private static final class Recording {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final FtMetricsRecorder recorder;

        Recording(boolean failing) {
            recorder = (FtMetricsRecorder) Proxy.newProxyInstance(FtMetricsRecorder.class.getClassLoader(),
                    new Class<?>[]{FtMetricsRecorder.class}, (self, method, args) -> {
                        switch (method.getName()) {
                            case "toString":
                                return "Recording";
                            case "hashCode":
                                return System.identityHashCode(self);
                            case "equals":
                                return self == args[0];
                            default:
                                break;
                        }
                        calls.add(method.getName());
                        if (failing) {
                            throw new IllegalStateException("recorder failure");
                        }
                        return null;
                    });
        }
    }

    private static void callEveryMethod(FtMetricsRecorder recorder) throws NoSuchMethodException {
        Method call = Service.class.getDeclaredMethod("call");
        recorder.register(Service.class, call, true, true, true, true, true, true);
        recorder.recordInvocation(Service.class, call, true, false, true);
        recorder.recordRetry(Service.class, call, 1, RetryResult.VALUE_RETURNED);
        recorder.recordTimeout(Service.class, call, false, 1L);
        recorder.recordCircuitBreakerCall(Service.class, call, CBCallResult.SUCCESS);
        recorder.notifyCircuitBreakerStateChange(Service.class, call, CBState.CLOSED, CBState.OPEN);
        recorder.recordBulkheadAccepted(Service.class, call, 1L, 2L);
        recorder.recordBulkheadRejected(Service.class, call);
        recorder.bulkheadRunningDelta(Service.class, call, 1);
        recorder.bulkheadWaitingDelta(Service.class, call, -1);
    }

    @AfterEach
    void resetGlobalOpenTelemetry() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void compositeFansEveryCallOutAndSurvivesAFailingRecorder() throws NoSuchMethodException {
        Recording failing = new Recording(true);
        Recording healthy = new Recording(false);
        FtMetricsRecorder composite = new CompositeFtMetricsRecorder(List.of(failing.recorder, healthy.recorder));

        callEveryMethod(composite);

        assertEquals(ALL_CALLS, failing.calls);
        assertEquals(ALL_CALLS, healthy.calls);
    }

    @Test
    void delegatingRecorderIsInactiveUntilItHasADelegate() throws NoSuchMethodException {
        DelegatingFtMetricsRecorder recorder = new DelegatingFtMetricsRecorder() { };
        assertFalse(recorder.active());
        // Without a delegate every call goes to the no-op recorder.
        callEveryMethod(recorder);

        Recording delegate = new Recording(false);
        recorder.delegateTo(delegate.recorder);
        callEveryMethod(recorder);

        assertTrue(recorder.active());
        assertEquals(ALL_CALLS, delegate.calls);
    }

    @Test
    void apiPresenceRequiresALoadableAndReadableClass() {
        assertTrue(DelegatingFtMetricsRecorder.apiPresent("java.lang.String"));
        assertTrue(DelegatingFtMetricsRecorder.apiPresent("io.opentelemetry.api.GlobalOpenTelemetry"));
        assertFalse(DelegatingFtMetricsRecorder.apiPresent("io.example.NoSuchApi"));
    }

    @Test
    void diracRecorderDelegatesToTheBaseRegistry() throws NoSuchMethodException {
        RecordingMetricRegistry registry = new RecordingMetricRegistry();
        DiracFtMetricsRecorder recorder = new DiracFtMetricsRecorder();
        recorder.baseRegistry = new FixedInstance(registry.registry).instance();

        recorder.init();
        recorder.recordBulkheadRejected(Service.class, Service.class.getDeclaredMethod("call"));

        assertTrue(recorder.active());
        assertEquals(1, registry.count(RecordingMetricRegistry.id("ft.bulkhead.calls.total",
                "method", Service.class.getName() + ".call", "bulkheadResult", "rejected")));
    }

    @Test
    void diracRecorderWithoutARegistryStaysInactive() {
        DiracFtMetricsRecorder recorder = new DiracFtMetricsRecorder();
        recorder.baseRegistry = new FixedInstance(null).instance();

        recorder.init();

        assertFalse(recorder.active());
    }

    @Test
    void otelRecorderDelegatesToGlobalOpenTelemetry() throws NoSuchMethodException {
        RecordingOpenTelemetry recording = new RecordingOpenTelemetry();
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(recording.openTelemetry());
        OtelFtMetricsRecorder recorder = new OtelFtMetricsRecorder();

        recorder.init();
        recorder.recordBulkheadRejected(Service.class, Service.class.getDeclaredMethod("call"));

        assertTrue(recorder.active());
        assertEquals(1.0, recording.sum("ft.bulkhead.calls.total", "bulkheadResult", "rejected"));
    }

    @Test
    void noopRecorderAcceptsEveryCall() {
        assertDoesNotThrow(() -> callEveryMethod(FtMetricsRecorder.NOOP));
    }

    @Test
    void resolverKeepsOnlyActiveRecorders() {
        DelegatingFtMetricsRecorder inactive = new DelegatingFtMetricsRecorder() { };
        Recording active = new Recording(false);

        assertEquals(FtMetricsRecorder.NOOP, MetricsRecorderResolver.resolve(null));
        assertEquals(FtMetricsRecorder.NOOP, MetricsRecorderResolver.resolve(new FixedInstance(null).instance()));
        assertEquals(FtMetricsRecorder.NOOP, MetricsRecorderResolver.resolve(new FixedInstance(inactive).instance()));
        assertEquals(active.recorder, MetricsRecorderResolver.resolve(new FixedInstance(active.recorder).instance()));
    }

    @Test
    void enumsCarryTheSpecificationTagValues() {
        assertEquals(List.of("valueReturned", "exceptionNotRetryable", "maxRetriesReached", "maxDurationReached"),
                Arrays.stream(RetryResult.values()).map(RetryResult::tagValue).toList());
        assertEquals(List.of("success", "failure", "circuitBreakerOpen"),
                Arrays.stream(CBCallResult.values()).map(CBCallResult::tagValue).toList());
        assertEquals(List.of("closed", "open", "halfOpen"),
                Arrays.stream(CBState.values()).map(CBState::tagValue).toList());
    }
}
