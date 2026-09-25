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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class ContinuousTestingTest {

    /** A run the test ends by hand. */
    static final class FakeRun implements ContinuousTesting.Launched {
        final List<String> tests;
        final CompletableFuture<Integer> exit = new CompletableFuture<>();
        volatile boolean cancelled;
        /** When set, waitFor() holds the exit code until it opens: the process ended, the worker has not yet read it. */
        volatile CountDownLatch collected;

        FakeRun(List<String> tests) {
            this.tests = tests;
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                int code = exit.get();
                CountDownLatch hold = collected;
                if (hold != null) {
                    hold.await();
                }
                return code;
            } catch (ExecutionException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void cancel() {
            if (!exit.isDone()) {
                cancelled = true;
                exit.complete(143);
            }
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        void finish(int code) {
            exit.complete(code);
        }
    }

    private final BlockingQueue<FakeRun> launches = new LinkedBlockingQueue<>();
    private final BlockingQueue<TestResults> results = new LinkedBlockingQueue<>();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final AtomicReference<SurefireReports.Reports> reports = new AtomicReference<>(passed(2));
    private Path resultsFile;
    private ContinuousTesting testing;

    @BeforeEach
    void start(@TempDir Path dir) {
        resultsFile = dir.resolve(TestResultsFile.FILE_NAME);
        testing = new ContinuousTesting(tests -> {
            FakeRun run = new FakeRun(tests);
            launches.add(run);
            return run;
        }, since -> reports.get(), () -> true, resultsFile, "target/vidocq-dev-tests.log", results::add, warnings::add,
                Clock.systemUTC());
        testing.start();
    }

    @AfterEach
    void close() {
        testing.close();
    }

    private static SurefireReports.Reports passed(int run) {
        return new SurefireReports.Reports(new Counts(run, 0, 0, 0), List.of(), 1, 0);
    }

    private static SurefireReports.Reports failed(String... tests) {
        List<Failure> failures = new ArrayList<>();
        for (String test : tests) {
            failures.add(new Failure(test, "org.opentest4j.AssertionFailedError", "no"));
        }
        return new SurefireReports.Reports(new Counts(tests.length + 1, tests.length, 0, 0), failures, 1, 0);
    }

    private FakeRun nextLaunch() throws InterruptedException {
        FakeRun run = launches.poll(5, TimeUnit.SECONDS);
        assertNotNull(run, "a run should have started");
        return run;
    }

    private TestResults nextResult() throws InterruptedException {
        TestResults result = results.poll(5, TimeUnit.SECONDS);
        assertNotNull(result, "a run should have ended");
        return result;
    }

    /** Ends a first run with {@code outcome}, so that the next ones have a previous result. */
    private void firstRun(SurefireReports.Reports outcome) throws InterruptedException {
        reports.set(outcome);
        testing.changed(Trigger.RUN_ALL, TestControl.ReadyGate.NOW);
        nextLaunch().finish(outcome.counts().failures() > 0 ? 1 : 0);
        nextResult();
    }

    @Test
    void aChangeRunsEveryTestOnceItsGateOpens() throws Exception {
        CountDownLatch reloaded = new CountDownLatch(1);
        testing.changed(Trigger.CHANGE, abandoned -> {
            reloaded.await();
            return true;
        });

        assertNull(launches.poll(300, TimeUnit.MILLISECONDS), "no run before the reload completed");
        reloaded.countDown();
        FakeRun run = nextLaunch();
        assertEquals(List.of(), run.tests);
        run.finish(0);

        TestResults result = nextResult();
        assertEquals(State.PASSED, result.state());
        assertEquals(Trigger.CHANGE, result.trigger());
        assertTrue(Files.readString(resultsFile).startsWith("{\"state\":\"passed\",\"trigger\":\"change\""));
    }

    @Test
    void aChangeCancelsTheRunInFlightAndRunsAgain() throws Exception {
        testing.changed(Trigger.CHANGE, TestControl.ReadyGate.NOW);
        FakeRun first = nextLaunch();

        testing.changed(Trigger.TEST_CHANGE, TestControl.ReadyGate.NOW);

        assertTrue(first.cancelled);
        TestResults cancelled = nextResult();
        assertEquals(State.CANCELLED, cancelled.state());
        assertEquals(Trigger.CHANGE, cancelled.trigger());
        nextLaunch().finish(0);
        assertEquals(Trigger.TEST_CHANGE, nextResult().trigger());
    }

    @Test
    void aRequestDuringARunIsQueuedLastWinsAndNeverCancels() throws Exception {
        firstRun(failed("com.acme.ATest#x"));
        testing.changed(Trigger.TEST_CHANGE, TestControl.ReadyGate.NOW);
        FakeRun inFlight = nextLaunch();

        assertEquals(ContinuousTesting.QUEUED, testing.request(Trigger.RERUN_FAILED));
        assertEquals(ContinuousTesting.QUEUED, testing.request(Trigger.RUN_ALL));

        assertFalse(inFlight.cancelled, "a request never cancels");
        assertNull(launches.poll(200, TimeUnit.MILLISECONDS), "one run at a time");
        inFlight.finish(1);
        nextResult();
        FakeRun queued = nextLaunch();
        assertEquals(List.of(), queued.tests, "run-all, the last request, won");
        queued.finish(0);
        assertEquals(Trigger.RUN_ALL, nextResult().trigger());
    }

    @Test
    void rerunFailedRunsOnlyTheLastFailures() throws Exception {
        firstRun(failed("com.acme.ATest#x", "com.acme.BTest#y(int)[1]", "com.acme.BTest#y(int)[2]"));

        assertEquals(ContinuousTesting.QUEUED, testing.request(Trigger.RERUN_FAILED));

        FakeRun rerun = nextLaunch();
        assertEquals(List.of("com.acme.ATest#x", "com.acme.BTest#y"), rerun.tests);
        rerun.finish(0);
        assertEquals(Trigger.RERUN_FAILED, nextResult().trigger());
    }

    @Test
    void rerunFailedWithNothingFailedStartsNothing() throws Exception {
        firstRun(passed(3));

        assertEquals(ContinuousTesting.NOTHING_TO_RERUN, testing.request(Trigger.RERUN_FAILED));

        assertNull(launches.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void interruptDropsTheChangeWaitingForItsGate() throws Exception {
        testing.changed(Trigger.CHANGE, abandoned -> {
            while (!abandoned.getAsBoolean()) {
                Thread.sleep(10);
            }
            return false;
        });
        Thread.sleep(100);

        testing.interrupt();

        assertNull(launches.poll(300, TimeUnit.MILLISECONDS), "a failed recompile runs no test");
    }

    @Test
    void theRunningStateKeepsThePreviousResult() throws Exception {
        firstRun(passed(2));
        testing.changed(Trigger.TEST_CHANGE, TestControl.ReadyGate.NOW);
        FakeRun run = nextLaunch();

        String json = Files.readString(resultsFile);

        assertTrue(json.startsWith("{\"state\":\"running\",\"trigger\":\"test-change\""), json);
        assertTrue(json.contains("\"previous\":{\"state\":\"passed\""), json);
        run.finish(0);
    }

    @Test
    void closingCancelsTheRunInFlight() throws Exception {
        testing.changed(Trigger.RUN_ALL, TestControl.ReadyGate.NOW);
        FakeRun run = nextLaunch();

        testing.close();

        assertTrue(run.cancelled);
        assertEquals(State.CANCELLED, nextResult().state());
        testing.close();
    }

    @Test
    void aLauncherThatCannotStartIsAnErrorNotACrash(@TempDir Path dir) throws Exception {
        testing.close();
        testing = new ContinuousTesting(tests -> {
            throw new java.io.IOException("mvn: not found");
        }, since -> SurefireReports.Reports.NONE, () -> false, dir.resolve("r.json"), "log", results::add, warnings::add,
                Clock.systemUTC());
        testing.start();

        testing.changed(Trigger.RUN_ALL, TestControl.ReadyGate.NOW);

        assertEquals(State.ERROR, nextResult().state(), "Maven never ran: nothing was compiled");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("mvn: not found")), warnings.toString());
    }

    /** Review finding: a request between interrupt() and the reload must not run test-compile beside the recompile. */
    @Test
    void aRequestDuringARecompileWaitsForItsEnd() throws Exception {
        firstRun(passed(2));
        testing.interrupt();

        assertEquals(ContinuousTesting.QUEUED, testing.request(Trigger.RUN_ALL));

        assertNull(launches.poll(300, TimeUnit.MILLISECONDS), "no run while the application recompiles");
        testing.release();
        FakeRun run = nextLaunch();
        run.finish(0);
        assertEquals(Trigger.RUN_ALL, nextResult().trigger());
    }

    /** Review finding: a test change during the reload keeps waiting for the reload. */
    @Test
    void aTestChangeDuringTheReloadWaitsForTheReloadToo() throws Exception {
        CountDownLatch reloaded = new CountDownLatch(1);
        testing.changed(Trigger.CHANGE, abandoned -> {
            while (reloaded.getCount() > 0) {
                if (abandoned.getAsBoolean()) {
                    return false;
                }
                Thread.sleep(10);
            }
            return true;
        });
        Thread.sleep(100);

        testing.changed(Trigger.TEST_CHANGE, TestControl.ReadyGate.NOW);

        assertNull(launches.poll(300, TimeUnit.MILLISECONDS), "no run before the reload completed");
        reloaded.countDown();
        FakeRun run = nextLaunch();
        run.finish(0);
        assertEquals(Trigger.CHANGE, nextResult().trigger(), "the change's run covers the test change");
        assertNull(launches.poll(300, TimeUnit.MILLISECONDS), "one run, not two");
    }

    /** #138: a change that arrives once the run's process ended, before the worker read its outcome. */
    @Test
    void aChangeAfterTheRunEndedDoesNotMarkItCancelled() throws Exception {
        testing.changed(Trigger.CHANGE, TestControl.ReadyGate.NOW);
        FakeRun run = nextLaunch();
        CountDownLatch collected = new CountDownLatch(1);
        run.collected = collected;
        run.finish(0);
        Thread.sleep(100);

        testing.changed(Trigger.TEST_CHANGE, TestControl.ReadyGate.NOW);
        collected.countDown();

        TestResults finished = nextResult();
        assertEquals(State.PASSED, finished.state(), "the run had finished before the change");
        assertEquals(finished, testing.lastComplete());
        nextLaunch().finish(0);
        assertEquals(Trigger.TEST_CHANGE, nextResult().trigger());
    }

    /** #138: a rerun whose names -Dtest matched to no test runs every test, rather than losing the failures. */
    @Test
    void aRerunThatMatchesNoTestRunsEveryTestInstead() throws Exception {
        firstRun(failed("com.acme.ATest#x"));
        testing.request(Trigger.RERUN_FAILED);
        FakeRun rerun = nextLaunch();
        reports.set(SurefireReports.Reports.NONE);
        rerun.finish(0);
        assertEquals(State.NO_TESTS, nextResult().state());

        FakeRun all = nextLaunch();

        assertEquals(List.of(), all.tests, "every test");
        reports.set(failed("com.acme.ATest#x"));
        all.finish(1);
        TestResults again = nextResult();
        assertEquals(Trigger.RUN_ALL, again.trigger());
        assertEquals(List.of("com.acme.ATest#x"), again.rerunList());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("matched no test")), warnings.toString());
    }
}
