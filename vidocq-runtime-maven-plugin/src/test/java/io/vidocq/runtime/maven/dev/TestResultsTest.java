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
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.maven.dev.TestResults.Counts;
import io.vidocq.runtime.maven.dev.TestResults.Failure;
import io.vidocq.runtime.maven.dev.TestResults.State;
import io.vidocq.runtime.maven.dev.TestResults.Trigger;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestResultsTest {

    private static final Instant AT = Instant.parse("2026-09-25T10:12:03Z");
    private static final String LOG = "target/vidocq-dev-tests.log";

    private final List<String> warnings = new ArrayList<>();

    private TestResults completed(int exit, SurefireReports.Reports reports) {
        return TestResults.completed(Trigger.CHANGE, AT, 3210, exit, true, reports, LOG, warnings::add);
    }

    private static SurefireReports.Reports reports(Counts counts, List<Failure> failures, int readable,
            int unreadable) {
        return new SurefireReports.Reports(counts, failures, readable, unreadable);
    }

    private static final Failure FAILURE =
            new Failure("com.acme.OrderServiceTest#rejectsEmptyCart", "org.opentest4j.AssertionFailedError", "no");

    @Test
    void allGreenIsPassed() {
        assertEquals(State.PASSED, completed(0, reports(new Counts(3, 0, 0, 1), List.of(), 1, 0)).state());
        assertEquals(List.of(), warnings);
    }

    @Test
    void aFailureOrAnErrorIsFailed() {
        assertEquals(State.FAILED, completed(1, reports(new Counts(3, 1, 0, 0), List.of(FAILURE), 1, 0)).state());
        assertEquals(State.FAILED, completed(1, reports(new Counts(3, 0, 1, 0), List.of(FAILURE), 1, 0)).state());
    }

    @Test
    void noReportAndANonZeroExitIsACompileError() {
        assertEquals(State.COMPILE_ERROR, completed(1, SurefireReports.Reports.NONE).state());
    }

    @Test
    void noReportAndExitZeroIsNoTests() {
        assertEquals(State.NO_TESTS, completed(0, SurefireReports.Reports.NONE).state());
        assertEquals(State.NO_TESTS, completed(0, reports(Counts.NONE, List.of(), 1, 0)).state());
    }

    @Test
    void unreadableReportsAndExitZeroArePassedWithAWarning() {
        assertEquals(State.PASSED, completed(0, reports(Counts.NONE, List.of(), 0, 2)).state());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains(LOG), warnings.getFirst());
    }

    @Test
    void aCrashedForkWithNoFailureIsFailedNeverPassed() {
        assertEquals(State.FAILED, completed(1, reports(new Counts(3, 0, 0, 0), List.of(), 1, 0)).state());
        assertTrue(warnings.getFirst().contains("exited with 1"), warnings.getFirst());
    }

    @Test
    void theRerunListCutsParameterisedNamesAndKeepsEachMethodOnce() {
        TestResults failed = completed(1, reports(new Counts(4, 3, 0, 0), List.of(
                new Failure("com.acme.MathTest#adds(int)[1]", "T", "m"),
                new Failure("com.acme.MathTest#adds(int)[2]", "T", "m"),
                new Failure("com.acme.MathTest$Nested#divides", "T", "m")), 1, 0));

        assertEquals(List.of("com.acme.MathTest#adds", "com.acme.MathTest$Nested#divides"), failed.rerunList());
    }

    @Test
    void runningAndCancelledKeepAFlatPrevious() {
        TestResults passed = completed(0, reports(new Counts(2, 0, 0, 0), List.of(), 1, 0));
        TestResults running = TestResults.running(Trigger.TEST_CHANGE, AT, LOG, passed);
        TestResults cancelled = TestResults.cancelled(Trigger.TEST_CHANGE, AT, 10, LOG, running);

        assertEquals(State.RUNNING, running.state());
        assertEquals(passed, running.previous());
        assertEquals(State.RUNNING, cancelled.previous().state());
        assertNull(cancelled.previous().previous(), "the file never nests deeper than one level");
    }

    @Test
    void onlyTheTwoRequestWordsAreTriggers() {
        assertEquals(Optional.of(Trigger.RUN_ALL), Trigger.ofRequest("run-all"));
        assertEquals(Optional.of(Trigger.RERUN_FAILED), Trigger.ofRequest("rerun-failed"));
        assertEquals(Optional.empty(), Trigger.ofRequest("change"));
        assertEquals(Optional.empty(), Trigger.ofRequest("rm -rf"));
    }

    @Test
    void theWireValuesAreTheSpecs() {
        assertEquals(List.of("running", "passed", "failed", "compile-error", "no-tests", "cancelled", "error"),
                List.of(State.values()).stream().map(State::wire).toList());
        assertEquals(List.of("change", "test-change", "run-all", "rerun-failed"),
                List.of(Trigger.values()).stream().map(Trigger::wire).toList());
    }

    /** #138: Maven failed before any test, and not in the compiler: a missing Surefire provider offline, a crash. */
    @Test
    void noReportANonZeroExitAndNoCompilerErrorIsAnError() {
        TestResults result = TestResults.completed(Trigger.CHANGE, AT, 3210, 1, false, SurefireReports.Reports.NONE,
                LOG, warnings::add);

        assertEquals(State.ERROR, result.state());
        assertTrue(result.state().complete(), "a finished run");
    }

    /** #138: a failure of the class itself (@BeforeAll) has no method: the whole class runs again. */
    @Test
    void aClassLevelFailureRerunsTheWholeClass() {
        TestResults failed = completed(1, reports(new Counts(2, 0, 1, 0), List.of(
                new Failure("com.acme.SetupTest#", "T", "m"),
                new Failure("com.acme.OtherTest#x", "T", "m")), 1, 0));

        assertEquals(List.of("com.acme.SetupTest", "com.acme.OtherTest#x"), failed.rerunList());
    }
}
