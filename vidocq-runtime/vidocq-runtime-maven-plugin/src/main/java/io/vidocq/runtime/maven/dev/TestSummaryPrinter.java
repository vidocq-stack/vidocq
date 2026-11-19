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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The terminal lines of a run (spec §4.3): the headline, one line per failure — {@value #MAX_FAILURE_LINES} at most,
 * with where the test failed — and the log, with the keys when a console is attached ({@code vidocq:test}).
 * {@code vidocq:dev} prints the failures too, without the keys, and a green run as its headline alone
 * (BUG-20261030-08). Messages come masked from {@link SurefireReports}.
 */
final class TestSummaryPrinter {

    /** The most failures listed; the others are counted. */
    static final int MAX_FAILURE_LINES = 10;
    /** The key hints, after the log. */
    static final String KEYS = "   [r] run all  [f] rerun failed  [q] quit";

    private TestSummaryPrinter() {}

    static String headline(TestResults r) {
        String trigger = r.trigger().wire();
        return switch (r.state()) {
            case PASSED, FAILED -> String.format(Locale.ROOT, "Tests: %d passed, %d failed, %d skipped in %.1f s (%s)",
                    r.counts().passed(), r.counts().failures() + r.counts().errors(), r.counts().skipped(),
                    r.durationMillis() / 1000.0, trigger);
            case COMPILE_ERROR -> "Tests: compilation failed (" + trigger + "), see " + r.log();
            case ERROR -> "Tests: the run failed (" + trigger + "), see " + r.log();
            case NO_TESTS -> "Tests: no test to run (" + trigger + ")";
            case CANCELLED -> "Tests: cancelled (" + trigger + ")";
            case RUNNING -> "Tests: running (" + trigger + ")";
        };
    }

    /**
     * @param full {@code false} for {@code vidocq:dev}: no keys, and the headline alone when nothing failed
     * @param keys whether to add the key hints to the log line
     */
    static List<String> lines(TestResults r, boolean full, boolean keys) {
        List<String> lines = new ArrayList<>();
        lines.add(headline(r));
        List<TestResults.Failure> failures = r.failures();
        if (!full && failures.isEmpty()) {
            return lines;
        }
        for (int i = 0; i < Math.min(failures.size(), MAX_FAILURE_LINES); i++) {
            lines.add(failureLine(failures.get(i)));
        }
        if (failures.size() > MAX_FAILURE_LINES) {
            lines.add("  … and " + (failures.size() - MAX_FAILURE_LINES) + " more in " + r.log());
        }
        lines.add("Log: " + r.log() + (keys && full ? KEYS : ""));
        return lines;
    }

    /** {@code  FAILED <test> — <simple type>: <message>}. */
    static String failureLine(TestResults.Failure failure) {
        String type = failure.type().substring(failure.type().lastIndexOf('.') + 1);
        String what;
        if (type.isEmpty()) {
            what = failure.message();
        } else {
            what = failure.message().isEmpty() ? type : type + ": " + failure.message();
        }
        return "  FAILED " + failure.test() + (what.isEmpty() ? "" : " — " + what)
                + (failure.at().isEmpty() ? "" : " (" + failure.at() + ")");
    }
}
