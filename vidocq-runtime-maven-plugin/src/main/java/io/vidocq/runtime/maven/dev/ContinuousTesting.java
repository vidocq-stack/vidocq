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

import io.vidocq.runtime.maven.dev.TestResults.Trigger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Continuous testing (spec §2), shared by {@code vidocq:dev} and {@code vidocq:test}: one worker thread,
 * {@code vidocq-tests}, runs the tests one run at a time.
 *
 * <ul>
 *   <li>A {@linkplain #changed change} cancels the run in flight and replaces the run waiting to start; that run
 *       starts once its {@link ReadyGate} opens, after the application reloaded.</li>
 *   <li>A {@linkplain #request request} ({@code run-all}, {@code rerun-failed}) never cancels: it waits for the run
 *       in flight, the last one wins, and it never replaces a change waiting to start, whose run covers it.</li>
 *   <li>{@code rerun-failed} runs the failures of the last complete result, which this class computes itself.</li>
 * </ul>
 * Each run writes {@code running}, then its outcome, to the results file, and hands the outcome to
 * {@code onResult}. Nothing a run does escapes the worker as an exception: it becomes a warning.
 */
final class ContinuousTesting implements TestControl, AutoCloseable {

    /** Starts one run of the tests: all of them, or the listed {@code Class#method}s. */
    interface Launcher {
        Launched launch(List<String> tests) throws IOException;
    }

    /** A run in flight. */
    interface Launched {

        /** Waits for the run to end and returns its exit code. */
        int waitFor() throws InterruptedException;

        /** Stops the run and every process it started, if it still runs; returns once they are gone. */
        void cancel();

        /** Whether {@link #cancel} stopped the run while it ran, as opposed to a run that had already ended (#138). */
        boolean cancelled();
    }

    /** What a request answers when it will run. */
    static final String QUEUED = "queued";
    /** What {@code rerun-failed} answers when the last result has no failure. */
    static final String NOTHING_TO_RERUN = "no failed test to rerun";
    /** How long {@link #close} waits for the worker. */
    static final Duration CLOSE_WAIT = Duration.ofSeconds(15);

    /** The run waiting to start. {@code change} is {@code false} for a request. */
    private record Pending(Trigger trigger, ReadyGate gate, boolean change) {}

    private final Launcher launcher;
    private final Function<Instant, SurefireReports.Reports> reports;
    private final BooleanSupplier compilerFailed;
    private final Path resultsFile;
    private final String log;
    private final Consumer<TestResults> onResult;
    private final Consumer<String> warn;
    private final Clock clock;

    private final Object lock = new Object();
    // Guarded by lock.
    private Pending pending;
    private Launched current;
    private boolean closed;
    /** Set by {@link #interrupt} while the application recompiles: requests wait, changes clear it. */
    private boolean held;
    /** The change the worker took and whose gate it is waiting on, or {@code null}. */
    private Pending waiting;
    /** Bumped by every change, interrupt and close: a run taken under an older value is abandoned. */
    private long generation;
    private TestResults lastComplete;
    private Thread worker;

    /**
     * @param reports        the reports of the run that started at the given instant
     * @param compilerFailed whether the log of the run that just ended shows a compiler failure (#138)
     * @param log        the log file as the results show it, such as {@code target/vidocq-dev-tests.log}
     * @param onResult   called on the worker with each run's outcome, never with {@code running}
     */
    ContinuousTesting(Launcher launcher, Function<Instant, SurefireReports.Reports> reports,
            BooleanSupplier compilerFailed, Path resultsFile, String log, Consumer<TestResults> onResult,
            Consumer<String> warn, Clock clock) {
        this.launcher = launcher;
        this.reports = reports;
        this.compilerFailed = compilerFailed;
        this.resultsFile = resultsFile;
        this.log = log;
        this.onResult = onResult;
        this.warn = warn;
        this.clock = clock;
    }

    /**
     * The continuous testing of a project: Surefire run by {@link TestRunner} with the Maven the recompile uses,
     * its reports read from {@code <buildDir>/surefire-reports}, the results in {@code <buildDir>/}{@value
     * TestResultsFile#FILE_NAME} and the log in {@code <buildDir>/}{@value TestResultsFile#LOG_NAME}.
     *
     * @param testProperties the dev session's keys, handed to every run as {@code -D}
     */
    static ContinuousTesting forProject(Path projectDir, Path buildDir, Map<String, String> testProperties,
            Consumer<TestResults> onResult, Consumer<String> warn) {
        Path logFile = buildDir.resolve(TestResultsFile.LOG_NAME);
        Path reportsDir = buildDir.resolve("surefire-reports");
        TestRunner runner = new TestRunner(projectDir, RecompileRunner.detectMavenExecutable(projectDir),
                testProperties, logFile, reportsDir);
        return new ContinuousTesting(runner, since -> SurefireReports.read(reportsDir, since),
                () -> TestRunner.compilationFailed(logFile), buildDir.resolve(TestResultsFile.FILE_NAME), shown(projectDir, logFile), onResult, warn,
                Clock.systemUTC());
    }

    /** {@code file} relative to the project, with forward slashes, when it is inside it. */
    static String shown(Path projectDir, Path file) {
        Path base = projectDir.toAbsolutePath().normalize();
        Path absolute = file.toAbsolutePath().normalize();
        return absolute.startsWith(base) ? base.relativize(absolute).toString().replace('\\', '/')
                : absolute.toString();
    }

    /** Starts the worker thread; idempotent. */
    void start() {
        synchronized (lock) {
            if (worker == null && !closed) {
                worker = Thread.ofPlatform().name("vidocq-tests").daemon(true).start(this::work);
            }
        }
    }

    @Override
    public void changed(Trigger trigger, ReadyGate gate) {
        Launched running;
        synchronized (lock) {
            if (closed) {
                return;
            }
            generation++;
            held = false;
            // A test change while a change (or the first run) waits for its reload keeps waiting for it: that run
            // covers every test, and a test run's Maven must not compile while the application boots (spec §2.2).
            Pending reloading = pending != null && pending.change() ? pending : waiting;
            pending = trigger == Trigger.TEST_CHANGE && reloading != null
                    ? reloading
                    : new Pending(trigger, gate, true);
            running = markCancelled();
            lock.notifyAll();
        }
        if (running != null) {
            running.cancel();
        }
    }

    @Override
    public void interrupt() {
        Launched running;
        synchronized (lock) {
            generation++;
            pending = null;
            held = true;
            running = markCancelled();
            lock.notifyAll();
        }
        if (running != null) {
            running.cancel();
        }
    }

    @Override
    public void release() {
        synchronized (lock) {
            held = false;
            lock.notifyAll();
        }
    }

    /**
     * A request from the dev console or the terminal (spec §3.2, §4.2).
     *
     * @return {@value #QUEUED}, or {@value #NOTHING_TO_RERUN} for {@code rerun-failed} after a result without
     *         failure, in which case nothing runs
     */
    String request(Trigger trigger) {
        synchronized (lock) {
            if (closed) {
                return "continuous testing is stopped";
            }
            if (trigger == Trigger.RERUN_FAILED && rerunTests().isEmpty()) {
                return NOTHING_TO_RERUN;
            }
            if (pending == null || !pending.change()) {
                pending = new Pending(trigger, ReadyGate.NOW, false);
            }
            lock.notifyAll();
            return QUEUED;
        }
    }

    /** The last result that is neither {@code running} nor {@code cancelled}, or {@code null}. */
    TestResults lastComplete() {
        synchronized (lock) {
            return lastComplete;
        }
    }

    /** Cancels the run in flight, which is written {@code cancelled}, and stops the worker; idempotent. */
    @Override
    public void close() {
        Launched running;
        Thread thread;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            generation++;
            pending = null;
            running = markCancelled();
            thread = worker;
            lock.notifyAll();
        }
        if (running != null) {
            running.cancel();
        }
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(CLOSE_WAIT.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Guarded by lock: the run in flight, for the caller to cancel unlocked. Whether the cancel stopped it is the
     * run's own to say ({@link Launched#cancelled}): a run whose process had already ended is not cancelled (#138).
     */
    private Launched markCancelled() {
        return current;
    }

    /** Guarded by lock. */
    private List<String> rerunTests() {
        return lastComplete == null ? List.of() : lastComplete.rerunList();
    }

    private boolean abandoned(long taken) {
        synchronized (lock) {
            return closed || generation != taken;
        }
    }

    private void work() {
        while (true) {
            Pending next;
            long taken;
            synchronized (lock) {
                while ((pending == null || held && !pending.change()) && !closed) {
                    try {
                        lock.wait();
                    } catch (InterruptedException stopping) {
                        return;
                    }
                }
                if (closed) {
                    return;
                }
                next = pending;
                pending = null;
                taken = generation;
                waiting = next.change() ? next : null;
            }
            try {
                boolean open = next.gate().await(() -> abandoned(taken));
                synchronized (lock) {
                    if (waiting == next) {
                        waiting = null;
                    }
                }
                if (open) {
                    runOnce(next.trigger(), taken);
                }
            } catch (InterruptedException stopping) {
                return;
            } catch (RuntimeException failed) {
                warn.accept("Tests: the run failed: " + failed.getClass().getName());
            }
        }
    }

    private void runOnce(Trigger trigger, long taken) throws InterruptedException {
        List<String> tests;
        TestResults previous;
        synchronized (lock) {
            if (closed || generation != taken) {
                return;
            }
            tests = trigger == Trigger.RERUN_FAILED ? rerunTests() : List.of();
            previous = lastComplete;
        }
        if (trigger == Trigger.RERUN_FAILED && tests.isEmpty()) {
            return;
        }
        Instant startedAt = clock.instant();
        long t0 = System.nanoTime();
        write(TestResults.running(trigger, startedAt, log, previous));
        Launched launched = null;
        boolean abandonedBeforeLaunch;
        synchronized (lock) {
            abandonedBeforeLaunch = closed || generation != taken;
            if (!abandonedBeforeLaunch) {
                try {
                    launched = launcher.launch(tests);
                } catch (IOException cannotStart) {
                    warn.accept("Tests: cannot start Maven: " + cannotStart.getMessage());
                }
                current = launched;
            }
        }
        if (abandonedBeforeLaunch) {
            write(TestResults.cancelled(trigger, startedAt, 0, log, previous));
            return;
        }
        int exit = launched == null ? -1 : launched.waitFor();
        synchronized (lock) {
            current = null;
        }
        boolean wasCancelled = launched != null && launched.cancelled();
        long duration = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        TestResults result = wasCancelled
                ? TestResults.cancelled(trigger, startedAt, duration, log, previous)
                : TestResults.completed(trigger, startedAt, duration, exit, compilerFailed.getAsBoolean(),
                        reports.apply(startedAt), log, warn);
        boolean rerunMatchedNothing = trigger == Trigger.RERUN_FAILED && result.state() == TestResults.State.NO_TESTS;
        if (result.state().complete() && !rerunMatchedNothing) {
            synchronized (lock) {
                lastComplete = result;
            }
        }
        write(result);
        onResult.accept(result);
        if (rerunMatchedNothing) {
            // -Dtest matched none of the failed tests, whose names it cannot express (a @DisplayName, a phrased
            // report): run them all rather than lose the failures (#138).
            warn.accept("Tests: the rerun of " + tests.size() + " failed test(s) matched no test; running every test");
            synchronized (lock) {
                if (pending == null && !closed) {
                    pending = new Pending(Trigger.RUN_ALL, ReadyGate.NOW, false);
                    lock.notifyAll();
                }
            }
        }
    }

    private void write(TestResults results) {
        try {
            TestResultsFile.write(resultsFile, results);
        } catch (IOException | RuntimeException failed) {
            warn.accept("Tests: cannot write " + resultsFile + ": " + failed.getMessage());
        }
    }
}
