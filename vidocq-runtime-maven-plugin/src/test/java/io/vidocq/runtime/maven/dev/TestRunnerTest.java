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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRunnerTest {

    private static final Duration PID_TIMEOUT = Duration.ofSeconds(20);

    @Test
    void allTestsRunWithoutDevServicesOfTheirOwn() {
        assertEquals(List.of("mvn", "-ntp", "-o", "-q", "process-test-classes", "surefire:test",
                        "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false",
                        "-Dvidocq.dev.devServices=false"),
                TestRunner.command("mvn", List.of(), Map.of(), true));
    }

    /** BUG-20261106-01: the run again after an offline miss goes online, its command otherwise the same. */
    @Test
    void anOnlineRunHasNoOfflineSwitch() {
        assertEquals(List.of("mvn", "-ntp", "-q", "process-test-classes", "surefire:test",
                        "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false",
                        "-Dvidocq.dev.devServices=false"),
                TestRunner.command("mvn", List.of(), Map.of(), false));
    }

    @Test
    void aRerunNamesItsTestsAndTheDevKeysFollowInOrder() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("vidocq.pool.url", "jdbc:postgresql://localhost:5432/dev");
        keys.put("vidocq.dev.provided.vidocq.pool.url", "postgres");
        keys.put("vidocq.dev.devServices", "true");

        assertEquals(List.of("/w/mvnw", "-ntp", "-o", "-q", "process-test-classes", "surefire:test",
                        "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false",
                        "-Dtest=com.acme.ATest#x,com.acme.BTest#y",
                        "-Dvidocq.pool.url=jdbc:postgresql://localhost:5432/dev",
                        "-Dvidocq.dev.provided.vidocq.pool.url=postgres",
                        "-Dvidocq.dev.devServices=false"),
                TestRunner.command("/w/mvnw", List.of("com.acme.ATest#x", "com.acme.BTest#y"), keys, true),
                "the dev session's own switch never reaches the tests: they use its containers");
    }

    private static Path script(Path dir, String body) throws Exception {
        Path maven = dir.resolve("fake-mvn");
        Files.writeString(maven, "#!/bin/sh\n" + body);
        assertTrue(maven.toFile().setExecutable(true));
        return maven;
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @Timeout(30)
    void aRunDeletesTheStaleReportsAndLogsItsOutput(@TempDir Path dir) throws Exception {
        Path reports = Files.createDirectories(dir.resolve("target/surefire-reports"));
        Files.writeString(reports.resolve("TEST-com.acme.Old.xml"), "<testsuite/>");
        Files.writeString(reports.resolve("notes.txt"), "kept");
        Path log = dir.resolve("target/vidocq-dev-tests.log");
        TestRunner runner = new TestRunner(dir, script(dir, "echo \"args: $*\"\n").toString(), Map.of(), log,
                reports);

        assertEquals(0, runner.launch(List.of()).waitFor());

        assertFalse(Files.exists(reports.resolve("TEST-com.acme.Old.xml")), "a stale report");
        assertTrue(Files.exists(reports.resolve("notes.txt")), "only the reports are deleted");
        assertTrue(Files.readString(log).contains("args: -ntp -o -q process-test-classes surefire:test"),
                Files.readString(log));
    }

    /** What Maven 3.9 logs, offline, for a test dependency that was never downloaded (BUG-20261106-01). */
    private static final String OFFLINE_MISS = "[ERROR] Failed to execute goal on project todo: Could not resolve"
            + " dependencies for project com.acme:todo:jar:1.0.0-SNAPSHOT\n[ERROR] dependency:"
            + " io.vidocq.runtime:vidocq-runtime-junit:jar:0.4.0-RC2 (test)\n[ERROR] \tCannot access central"
            + " (https://repo.maven.apache.org/maven2) in offline mode and the artifact"
            + " io.vidocq.runtime:vidocq-runtime-junit:jar:0.4.0-RC2 has not been downloaded from it before.\n";

    /** A fake Maven that misses an artifact offline and, online, logs its arguments in {@code online.txt}. */
    private static Path missingOffline(Path dir) throws Exception {
        Files.writeString(dir.resolve("offline-miss.txt"), OFFLINE_MISS);
        return script(dir, """
                case " $* " in
                  *" -o "*) cat offline-miss.txt; echo $$ > offline.pid; exit 1 ;;
                  *) echo "args: $*"; echo "args: $*" >> online.txt; exit 0 ;;
                esac
                """);
    }

    /**
     * BUG-20261106-01: the first run of a new project under {@code vidocq:dev} failed, offline, on the test
     * dependencies that {@code process-classes vidocq:dev} never resolves. A run that misses an artifact offline runs
     * again online, once, and the log is the online run's.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    @Timeout(30)
    void aRunThatMissesAnArtifactOfflineRunsAgainOnline(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("target/vidocq-dev-tests.log");
        TestRunner runner = new TestRunner(dir, missingOffline(dir).toString(), Map.of(), log,
                dir.resolve("target/surefire-reports"));

        ContinuousTesting.Launched launched = runner.launch(List.of("com.acme.ATest#x"));

        assertEquals(0, launched.waitFor());
        assertFalse(launched.cancelled());
        assertEquals(List.of("args: -ntp -q process-test-classes surefire:test -DfailIfNoTests=false"
                        + " -Dsurefire.failIfNoSpecifiedTests=false -Dtest=com.acme.ATest#x"
                        + " -Dvidocq.dev.devServices=false"),
                Files.readAllLines(dir.resolve("online.txt")), "one online run, the same tests");
        assertFalse(Files.readString(log).contains("offline mode"), Files.readString(log));
    }

    /** BUG-20261106-01: any other failure is the run's verdict, never a reason to run again. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    @Timeout(30)
    void aFailureThatIsNotAnOfflineMissDoesNotRunAgain(@TempDir Path dir) throws Exception {
        Path maven = script(dir, "echo \"args: $*\" >> calls.txt\necho '[ERROR] COMPILATION ERROR : '\nexit 1\n");
        TestRunner runner = new TestRunner(dir, maven.toString(), Map.of(), dir.resolve("target/t.log"),
                dir.resolve("target/surefire-reports"));

        assertEquals(1, runner.launch(List.of()).waitFor());

        assertEquals(1, Files.readAllLines(dir.resolve("calls.txt")).size(), "a single run");
    }

    /**
     * BUG-20261106-01: a cancel that comes after the offline run missed an artifact, before the online run starts,
     * stops the run: no online run starts, and the run is cancelled, since it had not ended yet.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    @Timeout(30)
    void aCancelBetweenTheOfflineMissAndTheOnlineRunStopsTheRun(@TempDir Path dir) throws Exception {
        TestRunner runner = new TestRunner(dir, missingOffline(dir).toString(), Map.of(),
                dir.resolve("target/t.log"), dir.resolve("target/surefire-reports"));
        ContinuousTesting.Launched launched = runner.launch(List.of());
        long offline = writtenPid(dir.resolve("offline.pid"));
        assertTrue(goneWithin(offline, Duration.ofSeconds(5)), "the offline run");

        launched.cancel();

        assertTrue(launched.cancelled());
        assertEquals(1, launched.waitFor());
        assertFalse(Files.exists(dir.resolve("online.txt")), "no online run after the cancel");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @Timeout(30)
    void cancellingKillsTheWholeProcessTree(@TempDir Path dir) throws Exception {
        Path maven = script(dir, "sleep 60 &\necho $! > child.pid\nwait\n");
        TestRunner runner = new TestRunner(dir, maven.toString(), Map.of(), dir.resolve("target/t.log"),
                dir.resolve("target/surefire-reports"));
        ContinuousTesting.Launched launched = runner.launch(List.of());
        long child = writtenPid(dir.resolve("child.pid"));

        launched.cancel();

        assertTrue(goneWithin(child, Duration.ofSeconds(5)), "the forked test JVM");
        assertNotEquals(0, launched.waitFor());
    }

    /**
     * #138: a cancel waits for the root Maven process only. A descendant that outlives it, such as a JVM ignoring
     * SIGTERM or a zombie that a container's PID 1 never reaps, is killed at once instead of costing the whole grace
     * period, which blocked the reload loop of vidocq:dev.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    @Timeout(30)
    void aDescendantThatIgnoresTheTermSignalDoesNotDelayTheCancel(@TempDir Path dir) throws Exception {
        // The child writes its own pid once its trap is set: the cancel below always meets a child ignoring TERM.
        Path maven = script(dir, "sh -c 'trap \"\" TERM; echo $$ > child.pid; while :; do sleep 0.1; done' &\nwait\n");
        TestRunner runner = new TestRunner(dir, maven.toString(), Map.of(), dir.resolve("target/t.log"),
                dir.resolve("target/surefire-reports"));
        ContinuousTesting.Launched launched = runner.launch(List.of());
        long child = writtenPid(dir.resolve("child.pid"));

        long t0 = System.nanoTime();
        launched.cancel();
        long millis = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(millis < TestRunner.GRACE.toMillis() / 2, "the cancel took " + millis + " ms");
        // SIGKILL is delivered asynchronously: give the kernel a moment, far below the grace period.
        assertTrue(goneWithin(child, Duration.ofSeconds(2)), "the descendant that ignored SIGTERM");
    }

    /** The pid the script wrote to {@code pidFile}, once it is there. */
    private static long writtenPid(Path pidFile) throws Exception {
        assertTrue(Poll.until(PID_TIMEOUT, () -> Files.exists(pidFile) && !Files.readString(pidFile).isBlank()),
                "the script writes the pid of its child");
        return Long.parseLong(Files.readString(pidFile).strip());
    }

    /**
     * {@link #gone} once the process had time to die: a signal is delivered, and acted on, after
     * {@code destroy} returns, so a check made right after the cancel races the kernel on a busy runner.
     */
    private static boolean goneWithin(long pid, Duration timeout) throws Exception {
        return Poll.until(timeout, () -> gone(pid));
    }

    /**
     * Whether {@code pid} is dead. On Linux a killed process that nobody reaps stays a zombie, which {@link
     * ProcessHandle#isAlive} still reports alive: in a container without an init process, the orphaned grandchild
     * is re-parented to a PID 1 that never reaps it. A zombie runs nothing and holds nothing: it counts as gone. So
     * does a process reaped between the two checks: reading its {@code /proc} entry then fails with "No such process".
     */
    private static boolean gone(long pid) throws Exception {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return true;
        }
        Path stat = Path.of("/proc", Long.toString(pid), "stat");
        if (!Files.isReadable(stat)) {
            return false;
        }
        String line;
        try {
            line = Files.readString(stat);
        } catch (IOException _) {
            return true;
        }
        return line.substring(line.lastIndexOf(')') + 2).startsWith("Z");
    }

    /** #138: only a compiler failure in the log makes a run without report a compile error. */
    @Test
    void theLogTellsACompilerFailureFromAnyOtherFailure(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("vidocq-dev-tests.log");

        assertFalse(TestRunner.compilationFailed(log), "no log at all");
        Files.writeString(log, "[ERROR] COMPILATION ERROR : \n[ERROR] /a/B.java:[3,1] ';' expected\n");
        assertTrue(TestRunner.compilationFailed(log));
        Files.writeString(log, "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.15.0:"
                + "testCompile (default-testCompile) on project app: Compilation failure\n");
        assertTrue(TestRunner.compilationFailed(log));
        Files.writeString(log, "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.5:test:"
                + " Cannot access central in offline mode and the artifact surefire-junit-platform has not been"
                + " downloaded from it before.\n");
        assertFalse(TestRunner.compilationFailed(log));
    }

    /** BUG-20261106-01: both ways Maven says an artifact is missing offline, and nothing else, are a miss. */
    @Test
    void theLogTellsAnArtifactMissingOfflineFromAnyOtherFailure(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("vidocq-dev-tests.log");

        assertFalse(TestRunner.missedOffline(log), "no log at all");
        Files.writeString(log, OFFLINE_MISS);
        assertTrue(TestRunner.missedOffline(log));
        Files.writeString(log, "[ERROR] Plugin org.apache.maven.plugins:maven-surefire-plugin:3.5.5 or one of its"
                + " dependencies could not be resolved: The repository system is offline but the artifact"
                + " org.apache.maven.surefire:surefire-junit-platform:jar:3.5.5 is not available in the local"
                + " repository.\n");
        assertTrue(TestRunner.missedOffline(log));
        Files.writeString(log, "[ERROR] COMPILATION ERROR : \n[ERROR] /a/B.java:[3,1] ';' expected\n");
        assertFalse(TestRunner.missedOffline(log));
        Files.writeString(log, "[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0\n");
        assertFalse(TestRunner.missedOffline(log));
    }
}
