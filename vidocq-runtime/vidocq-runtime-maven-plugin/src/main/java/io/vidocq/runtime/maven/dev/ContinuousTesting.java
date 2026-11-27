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
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Continuous testing (spec §2), shared by {@code vidocq:dev} and {@code vidocq:test}: one worker thread,
 * {@code vidocq-tests}, runs the tests one run at a time.
 *
 * <ul>
 *   <li>A {@linkplain #changed change} cancels the run in flight and replaces the run waiting to start; that run
 *       starts once its {@link ReadyGate} opens, after the application reloaded. What the cancelled run built is
 *       {@linkplain Launcher#discard discarded} first (BUG-20261117-01).</li>
 *   <li>A {@linkplain #request request} ({@code run-all}, {@code rerun-failed}) never cancels: it waits for the run
 *       in flight, the last one wins, and it never replaces a change waiting to start, whose run covers it.</li>
 *   <li>{@code rerun-failed} runs the failures of the last complete result, which this class computes itself.</li>
 *   <li>{@linkplain #pause Paused} (#798), a change runs nothing, and nothing is prepared; the first request that
 *       runs ends the pause for good.</li>
 * </ul>
 * Each run writes {@code running}, then its outcome, to the results file, and hands the outcome to
 * {@code onResult}. Nothing a run does escapes the worker as an exception: it becomes a warning.
 */
final class ContinuousTesting implements TestControl, AutoCloseable {

    /**
     * {@code vidocq.dev.continuousTesting} (#122, #798), resolved as the {@code vidocq.dev.*} switches of dev services
     * are: the explicit value, then the key in the application's files, then the default. A value is {@code true},
     * {@code false} or {@code paused}, trimmed and in any case.
     */
    enum Mode {

        /** The tests run at start, then after each reload and each test change. */
        ON,
        /**
         * Nothing runs, and the tests' own dev services do not start, until a request ({@code r}, or the dev console's
         * {@code tests} panel); then as {@link #ON} for the rest of the session.
         */
        PAUSED,
        /** No test runs, and nothing is watched for them. */
        OFF;

        /** Whether the session watches the test directories and reads the requests: all but {@link #OFF}. */
        boolean watches() {
            return this != OFF;
        }

        /**
         * The mode that wins: {@code explicit}, then {@code key} in {@code files}, then {@code defaultMode}; a blank value
         * is no value.
         *
         * @throws IllegalArgumentException when the value that wins is none of the three
         */
        static Mode resolve(String key, Optional<String> explicit,
                Function<String, Optional<String>> files, Mode defaultMode) {
            return explicit.filter(v -> !v.isBlank())
                    .or(() -> files.apply(key).filter(v -> !v.isBlank()))
                    .map(v -> parse(key, v))
                    .orElse(defaultMode);
        }

        /** @throws IllegalArgumentException naming {@code key} and the value when it is none of the three */
        static Mode parse(String key, String value) {
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "true" -> ON;
                case "false" -> OFF;
                case "paused" -> PAUSED;
                default -> throw new IllegalArgumentException(
                        key + " must be true, false or paused, not '" + value + "'");
            };
        }
    }

    /** Starts one run of the tests: all of them, or the listed {@code Class#method}s. */
    interface Launcher {

        /**
         * Readies what a run needs before it starts, such as the tests' own dev services at the first run of
         * {@code vidocq:dev} (BUG-20261114-08): on the worker, the run already shown {@code running}, and outside
         * the lock, so that a change or a close is never held back while containers start. Nothing by default.
         */
        default void prepare() {
            // nothing to ready
        }

        Launched launch(List<String> tests) throws IOException;

        /**
         * Deletes what the run that started at {@code since} built, once a change or a close stopped it: a run
         * stopped while it compiles leaves an output that the next build takes for up to date (BUG-20261117-01).
         * Called on the worker once the run is gone, before the next one starts. Nothing by default.
         */
        default void discard(Instant since) throws IOException {
            // nothing built
        }

        /** Stops what {@link #prepare} started, once {@link ContinuousTesting#close} ended the runs. */
        default void close() {
            // nothing started
        }
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
    /** What a request answers when it ends the {@linkplain #pause pause}: it runs, and so does every later change. */
    static final String STARTED = "continuous testing started: the tests run now, then after each change";
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
    /** Set by {@link #pause} until the first request that runs: a change runs nothing meanwhile. */
    private boolean paused;
    /** Set by {@link #interrupt} while the application recompiles: requests wait, changes clear it. */
    private boolean held;
    /** The change the worker took and whose gate it is waiting on, or {@code null}. */
    private Pending waiting;
    /** Bumped by every change, interrupt and close: a run taken under an older value is abandoned. */
    private long generation;
    private TestResults lastComplete;
    private Thread worker;

    /**
     * Where the results go: the results file, and the log file as the results show it, such as
     * {@code target/vidocq-dev-tests.log}.
     */
    record Results(Path file, String log) {}

    /**
     * @param reports        the reports of the run that started at the given instant
     * @param compilerFailed whether the log of the run that just ended shows a compiler failure (#138)
     * @param results        the results file and the log file
     * @param onResult   called on the worker with each run's outcome, never with {@code running}
     */
    ContinuousTesting(Launcher launcher, Function<Instant, SurefireReports.Reports> reports,
            BooleanSupplier compilerFailed, Results results, Consumer<TestResults> onResult,
            Consumer<String> warn, Clock clock) {
        this.launcher = launcher;
        this.reports = reports;
        this.compilerFailed = compilerFailed;
        this.resultsFile = results.file();
        this.log = results.log();
        this.onResult = onResult;
        this.warn = warn;
        this.clock = clock;
    }

    /**
     * The continuous testing of a project: Surefire run by {@link TestRunner} with the Maven the recompile uses,
     * its reports read from {@code <buildDir>/surefire-reports}, the results in {@code <buildDir>/}{@value
     * TestResultsFile#FILE_NAME} and the log in {@code <buildDir>/}{@value TestResultsFile#LOG_NAME}.
     *
     * @param services the dev services of the runs, whose keys every run gets as {@code -D}; closed with this
     * @param outputs  the outputs the runs build
     * @param info     gets the sources a run compiles again, saved while the previous run compiled them
     */
    static ContinuousTesting forProject(Path projectDir, Path buildDir, TestServices services, TestOutputs outputs,
            Consumer<TestResults> onResult, Consumer<String> info, Consumer<String> warn) {
        Path logFile = buildDir.resolve(TestResultsFile.LOG_NAME);
        Path reportsDir = buildDir.resolve("surefire-reports");
        TestRunner runner = new TestRunner(projectDir, RecompileRunner.detectMavenExecutable(projectDir),
                services, logFile, reportsDir, outputs, outputs.savedWhileCompiling(info, warn));
        return new ContinuousTesting(runner, since -> SurefireReports.read(reportsDir, since),
                () -> TestRunner.compilationFailed(logFile),
                new Results(buildDir.resolve(TestResultsFile.FILE_NAME), shown(projectDir, logFile)), onResult, warn,
                Clock.systemUTC());
    }

    /** {@code file} relative to the project, with forward slashes, when it is inside it. */
    static String shown(Path projectDir, Path file) {
        Path base = projectDir.toAbsolutePath().normalize();
        Path absolute = file.toAbsolutePath().normalize();
        return absolute.startsWith(base) ? base.relativize(absolute).toString().replace('\\', '/')
                : absolute.toString();
    }

    /**
     * Reads {@code r} and {@code f} from the terminal for {@code vidocq:dev} (#798), when a console is attached, on
     * the daemon thread {@code vidocq-dev-keys}: a platform thread, as {@code vidocq:test}'s, since it blocks on
     * standard input for the whole session and must never keep the JVM alive.
     *
     * @return whether a console is attached, hence whether keys are read
     */
    static boolean listenToDevKeys(Consumer<Trigger> request) {
        if (!TerminalControls.attached()) {
            return false;
        }
        TerminalControls controls = TerminalControls.forDev(System.in, command -> request.accept(
                command == TerminalControls.Command.RERUN_FAILED ? Trigger.RERUN_FAILED : Trigger.RUN_ALL));
        Thread.ofPlatform().name("vidocq-dev-keys").daemon(true).start(controls);
        return true;
    }

    /**
     * The keys typed in the console for a whole {@code vidocq:dev}: the predicate sends them to the consumer it gets
     * from then on, says whether they are read, and starts one reader only, whatever the sessions (#799).
     */
    static Predicate<Consumer<Trigger>> devKeys() {
        return new DevKeys(ContinuousTesting::listenToDevKeys)::listen;
    }

    /** Starts the worker thread; idempotent. */
    void start() {
        synchronized (lock) {
            if (worker == null && !closed) {
                worker = Thread.ofPlatform().name("vidocq-tests").daemon(true).start(this::work);
            }
        }
    }

    /**
     * Pauses continuous testing (#798): until a request runs, a change runs nothing, so that no Maven starts and the
     * launcher prepares nothing, such as the tests' own dev services. Called before the first change.
     */
    void pause() {
        synchronized (lock) {
            paused = true;
        }
    }

    /** Whether continuous testing waits for its first request. */
    boolean paused() {
        synchronized (lock) {
            return paused;
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
            if (paused) {
                // nothing runs while paused; the change still ends the hold of its recompile
                lock.notifyAll();
                return;
            }
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
     * @return {@value #QUEUED}, {@value #STARTED} when the request ends the {@linkplain #pause pause}, or
     *         {@value #NOTHING_TO_RERUN} for {@code rerun-failed} after a result without failure, in which case
     *         nothing runs
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
            if (paused) {
                paused = false;
                return STARTED;
            }
            return QUEUED;
        }
    }

    /** The last result that is neither {@code running} nor {@code cancelled}, or {@code null}. */
    TestResults lastComplete() {
        synchronized (lock) {
            return lastComplete;
        }
    }

    /**
     * Cancels the run in flight, which is written {@code cancelled}, stops the worker, then {@linkplain Launcher#close
     * closes} the launcher: the tests' own dev services stop after the last run; idempotent.
     */
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
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
        launcher.close();
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
        for (Taken taken = take(); taken != null; taken = take()) {
            if (!runTaken(taken)) {
                return;
            }
        }
    }

    /** The run the worker took, and the generation it was taken under. */
    private record Taken(Pending next, long generation) {}

    /** Waits for the next run; {@code null} once closed, or when the worker is interrupted. */
    private Taken take() {
        synchronized (lock) {
            while ((pending == null || held && !pending.change()) && !closed) {
                try {
                    lock.wait();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (closed) {
                return null;
            }
            Pending next = pending;
            pending = null;
            waiting = next.change() ? next : null;
            return new Taken(next, generation);
        }
    }

    /**
     * Waits for the taken run's gate, then runs it unless it was abandoned meanwhile.
     *
     * @return {@code false} when the worker was interrupted and must stop
     */
    private boolean runTaken(Taken taken) {
        Pending next = taken.next();
        try {
            boolean open = next.gate().await(() -> abandoned(taken.generation()));
            synchronized (lock) {
                if (waiting == next) {
                    waiting = null;
                }
            }
            if (open) {
                runOnce(next.trigger(), taken.generation());
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException failed) {
            warn.accept("Tests: the run failed: " + failed.getClass().getName());
        }
        return true;
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
        launcher.prepare();
        Launched launched = null;
        boolean abandonedBeforeLaunch;
        synchronized (lock) {
            abandonedBeforeLaunch = closed || generation != taken;
            if (!abandonedBeforeLaunch) {
                launched = launch(tests);
                current = launched;
            }
        }
        if (abandonedBeforeLaunch) {
            write(TestResults.cancelled(trigger, startedAt, 0, log, previous));
            return;
        }
        finish(trigger, tests, startedAt, t0, launched, previous);
    }

    /** Guarded by lock: starts the run, or warns and returns {@code null} when Maven cannot start. */
    private Launched launch(List<String> tests) {
        try {
            return launcher.launch(tests);
        } catch (IOException cannotStart) {
            warn.accept("Tests: cannot start Maven: " + cannotStart.getMessage());
            return null;
        }
    }

    /** Waits for the launched run (none when Maven could not start), then records and publishes its outcome. */
    private void finish(Trigger trigger, List<String> tests, Instant startedAt, long t0, Launched launched,
            TestResults previous) throws InterruptedException {
        int exit = launched == null ? -1 : launched.waitFor();
        synchronized (lock) {
            current = null;
        }
        boolean wasCancelled = launched != null && launched.cancelled();
        if (wasCancelled) {
            discard(startedAt);
        }
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
            queueRunAll();
        }
    }

    /**
     * Has the launcher delete what the run that started at {@code startedAt} built, once a change or a close stopped
     * it: on the worker, so before the next run starts (BUG-20261117-01). A failure is a warning, and the next run
     * builds on what is left.
     */
    private void discard(Instant startedAt) {
        try {
            launcher.discard(startedAt);
        } catch (IOException failed) {
            warn.accept("Tests: cannot delete what the stopped run built: " + failed.getMessage());
        }
    }

    /** Queues a run of every test, unless a run is already pending. */
    private void queueRunAll() {
        synchronized (lock) {
            if (pending == null && !closed) {
                pending = new Pending(Trigger.RUN_ALL, ReadyGate.NOW, false);
                lock.notifyAll();
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
