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

import io.vidocq.heisenberg.api.FtMetricsRecorder.RetryResult;
import java.util.List;

/**
 * Names, attribute keys and attribute values of the MicroProfile Fault Tolerance 4.1 metrics
 * (§9 MP Metrics, §10 OpenTelemetry), shared by {@link DiracFtMetrics} and {@link OtelFtMetrics}.
 */
final class FtMetricNames {

    static final String INVOCATIONS_TOTAL = "ft.invocations.total";
    static final String RETRY_CALLS_TOTAL = "ft.retry.calls.total";
    static final String RETRY_RETRIES_TOTAL = "ft.retry.retries.total";
    static final String TIMEOUT_CALLS_TOTAL = "ft.timeout.calls.total";
    static final String TIMEOUT_EXECUTION_DURATION = "ft.timeout.executionDuration";
    static final String CIRCUIT_BREAKER_CALLS_TOTAL = "ft.circuitbreaker.calls.total";
    static final String CIRCUIT_BREAKER_STATE_TOTAL = "ft.circuitbreaker.state.total";
    static final String CIRCUIT_BREAKER_OPENED_TOTAL = "ft.circuitbreaker.opened.total";
    static final String BULKHEAD_CALLS_TOTAL = "ft.bulkhead.calls.total";
    static final String BULKHEAD_EXECUTIONS_RUNNING = "ft.bulkhead.executionsRunning";
    static final String BULKHEAD_EXECUTIONS_WAITING = "ft.bulkhead.executionsWaiting";
    static final String BULKHEAD_RUNNING_DURATION = "ft.bulkhead.runningDuration";
    static final String BULKHEAD_WAITING_DURATION = "ft.bulkhead.waitingDuration";

    static final String METHOD = "method";
    static final String RESULT = "result";
    static final String FALLBACK = "fallback";
    static final String RETRIED = "retried";
    static final String RETRY_RESULT = "retryResult";
    static final String TIMED_OUT = "timedOut";
    static final String CIRCUIT_BREAKER_RESULT = "circuitBreakerResult";
    static final String STATE = "state";
    static final String BULKHEAD_RESULT = "bulkheadResult";

    static final String VALUE_RETURNED = "valueReturned";
    static final String EXCEPTION_THROWN = "exceptionThrown";
    static final String ACCEPTED = "accepted";
    static final String REJECTED = "rejected";

    private static final String APPLIED = "applied";
    private static final String NOT_APPLIED = "notApplied";
    private static final String NOT_DEFINED = "notDefined";

    private static final List<RetryResult> FIRST_ATTEMPT_RESULTS =
            List.of(RetryResult.VALUE_RETURNED, RetryResult.EXCEPTION_NOT_RETRYABLE);
    private static final List<RetryResult> ALL_RETRY_RESULTS = List.of(RetryResult.values());

    private FtMetricNames() {}

    /** The {@code fallback} values of {@code ft.invocations.total} a method can report. */
    static List<String> fallbackValues(boolean fallbackDefined) {
        return fallbackDefined ? List.of(APPLIED, NOT_APPLIED) : List.of(NOT_DEFINED);
    }

    /** The {@code fallback} value of {@code ft.invocations.total} for one invocation (MP FT 4.1 §9). */
    static String fallbackValue(boolean fallbackDefined, boolean fallbackApplied) {
        if (!fallbackDefined) {
            return NOT_DEFINED;
        }
        return fallbackApplied ? APPLIED : NOT_APPLIED;
    }

    /** The {@code retryResult} values of {@code ft.retry.calls.total}: the first attempt cannot run out of retries. */
    static List<RetryResult> retryResults(boolean retried) {
        return retried ? ALL_RETRY_RESULTS : FIRST_ATTEMPT_RESULTS;
    }
}
