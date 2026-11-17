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

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * A whole {@code vidocq:dev} session through {@link VidocqDevMojo#execute()}: the launch is prepared, the child JVM
 * started (it fails to boot: its main module does not exist), the source loop entered, and an interrupt ends the
 * session the way Ctrl+C does.
 */
class VidocqDevMojoSessionTest {

    private static final String STARTED = "INFO Child JVM started, pid=";

    @Test
    @Timeout(60)
    void aSessionPreparesTheLaunchStartsTheChildAndEndsOnInterrupt(@TempDir Path projectDir) throws Exception {
        SessionLog log = runUntilTheChildStarted(projectDir, false);

        assertEquals("0\n", Files.readString(projectDir.resolve("target/.vidocq-dev-reload"), StandardCharsets.UTF_8));
        assertTrue(log.lines.contains("INFO Vidocq dev — main module : no.such.module/no.such.Main"), log.lines::toString);
        assertTrue(log.lines.contains("INFO Watching: " + List.of(projectDir.resolve("src/main/java"),
                projectDir.resolve("src/main/resources"))), log.lines::toString);
        assertTrue(log.lines.stream().anyMatch(l -> l.startsWith("INFO Universal-loader mode:")), log.lines::toString);
        assertTrue(log.lines.contains("INFO Dev tools: no dev console, it needs vidocq-runtime-chappe-webserver-extension"),
                log.lines::toString);
        assertTrue(log.lines.stream().noneMatch(l -> l.startsWith("INFO Continuous testing")), log.lines::toString);
    }

    /** With continuous testing on, the test directories are watched and the first run is queued at start. */
    @Test
    @Timeout(60)
    void aSessionWithContinuousTestingWatchesTheTestDirectories(@TempDir Path projectDir) throws Exception {
        assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"), "a POSIX mvnw");
        // The tests' Maven: a wrapper that runs nothing.
        Path mvnw = Files.writeString(projectDir.resolve("mvnw"), "#!/bin/sh\nexit 0\n");
        assertTrue(mvnw.toFile().setExecutable(true));
        Files.createDirectories(projectDir.resolve("src/test/java"));

        SessionLog log = runUntilTheChildStarted(projectDir, true);

        assertTrue(log.lines.contains("INFO Continuous testing: on, watching " + List.of(
                projectDir.resolve("src/test/java"), projectDir.resolve("src/test/resources"))
                + " (-Dvidocq.dev.continuousTesting=false to turn it off)"), log.lines::toString);
    }

    /**
     * BUG-20261117-05: the watcher is there last. A file saved since Maven started, which no watcher saw, is compiled
     * before the child starts.
     */
    @Test
    @Timeout(60)
    void aFileSavedWhileTheSessionStartedIsCompiledBeforeTheChildStarts(@TempDir Path projectDir) throws Exception {
        assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"), "a POSIX mvnw");
        Instant mavenStarted = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(30);
        aMavenThatNotesItsRuns(projectDir);
        aSourceSaved(projectDir, mavenStarted.plusSeconds(2));

        SessionLog log = runUntilTheChildStarted(projectDir, false, mavenStarted);

        assertEquals(List.of("run"), Files.readAllLines(projectDir.resolve("runs.txt")));
        int compiled = firstLine(log, "INFO Menu.java was saved while vidocq:dev started: compiling it before the"
                + " application starts.");
        assertTrue(compiled >= 0 && compiled < firstLine(log, STARTED), log.lines::toString);
    }

    /** No margin: a file saved before Maven started is the build's, and the start compiles nothing more. */
    @Test
    @Timeout(60)
    void aFileSavedBeforeMavenStartedIsLeftToTheBuild(@TempDir Path projectDir) throws Exception {
        assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"), "a POSIX mvnw");
        Instant mavenStarted = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(30);
        aMavenThatNotesItsRuns(projectDir);
        aSourceSaved(projectDir, mavenStarted.minusSeconds(1));

        SessionLog log = runUntilTheChildStarted(projectDir, false, mavenStarted);

        assertFalse(Files.exists(projectDir.resolve("runs.txt")), log.lines::toString);
        assertTrue(log.lines.stream().noneMatch(l -> l.contains("was saved while vidocq:dev started")),
                log.lines::toString);
    }

    /**
     * A save of the start that does not compile: there is no application to keep up, so none starts. The session
     * waits for the next change, and Ctrl+C ends it.
     */
    @Test
    @Timeout(60)
    void noChildStartsWhileTheSavesOfTheStartDoNotCompile(@TempDir Path projectDir) throws Exception {
        assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"), "a POSIX mvnw");
        Instant mavenStarted = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(30);
        Path mvnw = Files.writeString(projectDir.resolve("mvnw"), "#!/bin/sh\necho run >> runs.txt\nexit 1\n");
        assertTrue(mvnw.toFile().setExecutable(true));
        aSourceSaved(projectDir, mavenStarted.plusSeconds(2));

        SessionLog log = runUntil("WARN What was saved while vidocq:dev started does not compile (exit 1)",
                projectDir, false, mavenStarted);

        assertEquals(List.of("run"), Files.readAllLines(projectDir.resolve("runs.txt")));
        assertEquals(-1, firstLine(log, STARTED), log.lines::toString);
        assertTrue(log.lines.contains("INFO Dev mode interrupted — shutting down."), log.lines::toString);
    }

    /**
     * The next change compiles again, and the child starts once the saves compile. The Maven of this test fails
     * once, and saves the source again while it does: the watcher, registered before the saves are compiled, reports
     * that save.
     */
    @Test
    @Timeout(60)
    void theChildStartsOnceTheSavesOfTheStartCompile(@TempDir Path projectDir) throws Exception {
        assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"), "a POSIX mvnw");
        Instant mavenStarted = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(30);
        Path mvnw = Files.writeString(projectDir.resolve("mvnw"), """
                #!/bin/sh
                echo run >> runs.txt
                if [ ! -e failed-once ]; then
                  touch failed-once
                  echo "// saved again" >> src/main/java/acme/Menu.java
                  exit 1
                fi
                """);
        assertTrue(mvnw.toFile().setExecutable(true));
        aSourceSaved(projectDir, mavenStarted.plusSeconds(2));

        SessionLog log = runUntilTheChildStarted(projectDir, false, mavenStarted);

        assertEquals(List.of("run", "run"), Files.readAllLines(projectDir.resolve("runs.txt")));
        int failed = firstLine(log, "WARN What was saved while vidocq:dev started does not compile (exit 1)");
        assertTrue(failed >= 0 && failed < firstLine(log, STARTED), log.lines::toString);
    }

    /** The session's Maven: a wrapper that notes each of its runs in {@code runs.txt}. */
    private static void aMavenThatNotesItsRuns(Path projectDir) throws IOException {
        Path mvnw = Files.writeString(projectDir.resolve("mvnw"), "#!/bin/sh\necho run >> runs.txt\n");
        assertTrue(mvnw.toFile().setExecutable(true));
    }

    private static void aSourceSaved(Path projectDir, Instant time) throws IOException {
        Path source = projectDir.resolve("src/main/java/acme/Menu.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package acme; class Menu {}");
        Files.setLastModifiedTime(source, FileTime.from(time));
    }

    /** The index of the first line that starts with {@code start}; {@code -1} when there is none. */
    private static int firstLine(SessionLog log, String start) {
        for (int i = 0; i < log.lines.size(); i++) {
            if (log.lines.get(i).startsWith(start)) {
                return i;
            }
        }
        return -1;
    }

    private static SessionLog runUntilTheChildStarted(Path projectDir, boolean continuousTesting) throws Exception {
        return runUntilTheChildStarted(projectDir, continuousTesting, null);
    }

    private static SessionLog runUntilTheChildStarted(Path projectDir, boolean continuousTesting, Instant mavenStarted)
            throws Exception {
        return runUntil(STARTED, projectDir, continuousTesting, mavenStarted);
    }

    /**
     * Runs {@code vidocq:dev} on a built project until it logs a line that starts with {@code awaited}, then
     * interrupts it as Ctrl+C does.
     *
     * @param mavenStarted when the Maven of the session started; {@code null} for a mojo without a Maven session
     */
    @SuppressWarnings("deprecation")
    private static SessionLog runUntil(String awaited, Path projectDir, boolean continuousTesting,
            Instant mavenStarted) throws Exception {
        Path classes = Files.createDirectories(projectDir.resolve("target/classes"));
        // A built output holds one class at least (BUG-20261030-12).
        Files.writeString(classes.resolve("Marker.class"), "built");
        Files.createDirectories(projectDir.resolve("src/main/java"));
        SessionLog log = new SessionLog(awaited);
        VidocqDevMojo mojo = new VidocqDevMojo();
        mojo.setLog(log);
        // As Maven hands it: a project always has a test output, the one the test runs build (BUG-20261117-01).
        MavenProject project = new MavenProject();
        project.getBuild().setTestOutputDirectory(projectDir.resolve("target/test-classes").toString());
        mojo.setProject(project);
        mojo.setBaseDir(projectDir.toFile());
        mojo.setBuildDir(projectDir.resolve("target").toFile());
        mojo.setClassesDir(classes.toFile());
        mojo.setMainModule("no.such.module");
        mojo.setMainClass("no.such.Main");
        mojo.setAppLayer("classes");
        mojo.setLayerMode(true);
        mojo.setHotReload(true);
        // No repository system here to resolve the hot swap agent: theAgentIsOnBothPaths... covers the launch.
        mojo.setHotSwap(false);
        mojo.setProfile("dev");
        mojo.setDevServices(false);
        mojo.setContinuousTesting(continuousTesting);
        mojo.setDebugOptions(false, 5005, false);
        mojo.setWatchDirs("src/main/java, ,src/main/resources");
        mojo.setTestWatchDirs("src/test/java,src/test/resources");
        if (mavenStarted != null) {
            mojo.setSession(new MavenSession(null, null,
                    new DefaultMavenExecutionRequest().setStartTime(Date.from(mavenStarted)),
                    new DefaultMavenExecutionResult()));
        }

        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread session = Thread.ofPlatform().name("vidocq-dev-session-test").start(() -> {
            try {
                mojo.execute();
            } catch (Exception e) {
                failure.set(e);
            }
        });
        assertTrue(log.said.await(30, TimeUnit.SECONDS), () -> "no line '" + awaited + "': " + log.lines);
        session.interrupt();
        session.join(TimeUnit.SECONDS.toMillis(30));

        assertFalse(session.isAlive(), "the session did not end on interrupt");
        assertNull(failure.get(), () -> "the session failed: " + failure.get());
        return log;
    }

    /**
     * Records the info and warning lines, and tells when one starts with the awaited text; thread-safe, the session
     * logs on its own thread.
     */
    private static final class SessionLog extends SystemStreamLog {

        final List<String> lines = new CopyOnWriteArrayList<>();
        final CountDownLatch said = new CountDownLatch(1);
        private final String awaited;

        SessionLog(String awaited) {
            this.awaited = awaited;
        }

        @Override
        public void info(CharSequence content) {
            add("INFO " + content);
        }

        @Override
        public void warn(CharSequence content) {
            add("WARN " + content);
        }

        private void add(String line) {
            lines.add(line);
            if (line.startsWith(awaited)) {
                said.countDown();
            }
        }
    }
}
