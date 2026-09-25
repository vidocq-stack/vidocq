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
import io.vidocq.runtime.maven.dev.TestResults.Trigger;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSummaryPrinterTest {

    private static final Instant AT = Instant.parse("2026-09-25T10:12:03Z");
    private static final String LOG = "target/vidocq-dev-tests.log";
    private static final Failure FAILURE = new Failure("com.acme.OrderServiceTest#rejectsEmptyCart",
            "org.opentest4j.AssertionFailedError", "expected: <400> but was: <200>");

    private static TestResults completed(int exit, Counts counts, List<Failure> failures) {
        return TestResults.completed(Trigger.CHANGE, AT, 3210, exit, true,
                new SurefireReports.Reports(counts, failures, 1, 0), LOG, warning -> {});
    }

    @Test
    void theSpecsThreeLines() {
        TestResults failed = completed(1, new Counts(42, 1, 0, 0), List.of(FAILURE));

        assertEquals(List.of(
                "Tests: 41 passed, 1 failed, 0 skipped in 3.2 s (change)",
                "  FAILED com.acme.OrderServiceTest#rejectsEmptyCart — AssertionFailedError: expected: <400> but"
                        + " was: <200>",
                "Log: target/vidocq-dev-tests.log   [r] run all  [f] rerun failed  [q] quit"),
                TestSummaryPrinter.lines(failed, true, true));
    }

    @Test
    void vidocqDevPrintsTheFirstLineOnlyAndNoKeysWithoutAConsole() {
        TestResults failed = completed(1, new Counts(42, 1, 0, 0), List.of(FAILURE));

        assertEquals(List.of("Tests: 41 passed, 1 failed, 0 skipped in 3.2 s (change)"),
                TestSummaryPrinter.lines(failed, false, true));
        assertEquals("Log: target/vidocq-dev-tests.log", TestSummaryPrinter.lines(failed, true, false).getLast());
    }

    @Test
    void errorsCountAsFailedAndSkippedAsSkipped() {
        assertEquals("Tests: 1 passed, 2 failed, 1 skipped in 3.2 s (change)",
                TestSummaryPrinter.headline(completed(1, new Counts(4, 1, 1, 1), List.of(FAILURE, FAILURE))));
    }

    @Test
    void theOtherStatesHaveOneLine() {
        assertEquals("Tests: compilation failed (change), see target/vidocq-dev-tests.log",
                TestSummaryPrinter.headline(completed(1, Counts.NONE, List.of())));
        assertEquals("Tests: no test to run (change)",
                TestSummaryPrinter.headline(completed(0, Counts.NONE, List.of())));
        assertEquals("Tests: cancelled (test-change)", TestSummaryPrinter.headline(
                TestResults.cancelled(Trigger.TEST_CHANGE, AT, 10, LOG, null)));
    }

    @Test
    void atMostTenFailuresAreListed() {
        List<Failure> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(FAILURE);
        }
        List<String> lines = TestSummaryPrinter.lines(completed(1, new Counts(12, 12, 0, 0), many), true, false);

        assertEquals(1 + 10 + 1 + 1, lines.size());
        assertEquals("  … and 2 more in target/vidocq-dev-tests.log", lines.get(11));
    }

    @Test
    void aFailureWithoutMessageShowsItsType() {
        assertEquals("  FAILED a.B#c — NullPointerException",
                TestSummaryPrinter.failureLine(new Failure("a.B#c", "java.lang.NullPointerException", "")));
    }

    @Test
    void aRunThatFailedOutsideTheCompilerSaysSo() {
        TestResults error = TestResults.completed(Trigger.CHANGE, AT, 3210, 1, false,
                SurefireReports.Reports.NONE, LOG, warning -> {});

        assertEquals("Tests: the run failed (change), see target/vidocq-dev-tests.log",
                TestSummaryPrinter.headline(error));
    }
}
