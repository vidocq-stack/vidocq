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

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One continuous-testing run, as {@code target/vidocq-dev-tests.json} holds it (spec §2.4): its state, what
 * triggered it, when it started and how long it took, its counts, its failures and errors, the log, and — for a run
 * still {@code running} or {@code cancelled} — the previous complete result, never nested deeper than that.
 *
 * @param previous the previous complete result for {@code running} and {@code cancelled}, else {@code null}
 */
record TestResults(State state, Trigger trigger, Instant startedAt, long durationMillis, Counts counts,
        List<Failure> failures, String log, TestResults previous) {

    /** {@code state} in the results file. */
    enum State {
        RUNNING("running"),
        PASSED("passed"),
        FAILED("failed"),
        COMPILE_ERROR("compile-error"),
        NO_TESTS("no-tests"),
        CANCELLED("cancelled"),
        /** Maven failed before any test ran, and not in the compiler: see the log (#138). */
        ERROR("error");

        private final String wire;

        State(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }

        /** Whether the run is over and whole: only such a result is kept as {@code previous} and for a rerun. */
        boolean complete() {
            return this != RUNNING && this != CANCELLED;
        }
    }

    /** {@code trigger} in the results file: what started the run. */
    enum Trigger {
        CHANGE("change"),
        TEST_CHANGE("test-change"),
        RUN_ALL("run-all"),
        RERUN_FAILED("rerun-failed");

        private final String wire;

        Trigger(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }

        /** The trigger a request names: {@code run-all} or {@code rerun-failed}, nothing else (spec §3.3). */
        static Optional<Trigger> ofRequest(String word) {
            return switch (word) {
                case "run-all" -> Optional.of(RUN_ALL);
                case "rerun-failed" -> Optional.of(RERUN_FAILED);
                default -> Optional.empty();
            };
        }
    }

    /** The testcases of a run: all of them, the failed, the in error, and the skipped. */
    record Counts(int run, int failures, int errors, int skipped) {

        static final Counts NONE = new Counts(0, 0, 0, 0);

        int passed() {
            return run - failures - errors - skipped;
        }
    }

    /**
     * A failed or erroneous test.
     *
     * @param test    {@code <class>#<method>}, as Surefire names the testcase
     * @param type    the exception's class
     * @param message the first line of its message, masked and cut (see {@link SurefireReports#firstLine})
     */
    record Failure(String test, String type, String message) {}

    TestResults {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(counts, "counts");
        Objects.requireNonNull(log, "log");
        failures = List.copyOf(failures);
    }

    /** A run that has just started; the previous complete result is kept for the panel and the terminal. */
    static TestResults running(Trigger trigger, Instant startedAt, String log, TestResults previous) {
        return new TestResults(State.RUNNING, trigger, startedAt, 0, Counts.NONE, List.of(), log, flat(previous));
    }

    /** A run a newer change stopped, or the goal's end; its previous result is kept. */
    static TestResults cancelled(Trigger trigger, Instant startedAt, long durationMillis, String log,
            TestResults previous) {
        return new TestResults(State.CANCELLED, trigger, startedAt, durationMillis, Counts.NONE, List.of(), log,
                flat(previous));
    }

    /**
     * A run whose subprocess exited (spec §2.4, §6): no report at all is {@code no-tests} after a zero exit, and after
     * a non-zero one {@code compile-error} when the log shows a compiler failure, else {@code error} (#138), unless reports were there but unreadable ({@code passed}, with a
     * warning); a failure or an error is {@code failed}; so is a non-zero exit without either, such as a forked JVM
     * that crashed, with a warning.
     */
    static TestResults completed(Trigger trigger, Instant startedAt, long durationMillis, int exitCode,
            boolean compilerFailed, SurefireReports.Reports reports, String log, Consumer<String> warn) {
        if (reports.unreadable() > 0) {
            warn.accept("Tests: " + reports.unreadable() + " Surefire report(s) could not be read; see " + log);
        }
        Counts counts = reports.counts();
        State state;
        if (reports.readable() == 0 || counts.run() == 0) {
            state = exitCode != 0 ? (compilerFailed ? State.COMPILE_ERROR : State.ERROR)
                    : reports.unreadable() > 0 ? State.PASSED : State.NO_TESTS;
        } else if (counts.failures() + counts.errors() > 0) {
            state = State.FAILED;
        } else if (exitCode != 0) {
            warn.accept("Tests: Maven exited with " + exitCode + " although no test failed; see " + log);
            state = State.FAILED;
        } else {
            state = State.PASSED;
        }
        return new TestResults(state, trigger, startedAt, durationMillis, counts, reports.failures(), log, null);
    }

    /**
     * The tests to run again, as Surefire's {@code -Dtest} takes them: {@code <class>#<method>}, each once, a
     * parameterised or repeated name such as {@code adds(int)[1]} cut to its method, which is what {@code -Dtest}
     * matches.
     */
    List<String> rerunList() {
        LinkedHashSet<String> tests = new LinkedHashSet<>();
        for (Failure failure : failures) {
            String test = failure.test();
            int cut = firstOf(test, '(', '[');
            tests.add(cut < 0 ? test : test.substring(0, cut));
        }
        return List.copyOf(tests);
    }

    private static int firstOf(String text, char a, char b) {
        int i = text.indexOf(a);
        int j = text.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }

    private static TestResults flat(TestResults previous) {
        if (previous == null || previous.previous() == null) {
            return previous;
        }
        return new TestResults(previous.state(), previous.trigger(), previous.startedAt(), previous.durationMillis(),
                previous.counts(), previous.failures(), previous.log(), null);
    }
}
