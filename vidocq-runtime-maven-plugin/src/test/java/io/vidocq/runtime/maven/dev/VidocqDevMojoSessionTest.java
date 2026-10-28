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

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** Runs {@code vidocq:dev} on a built project until its child started, then interrupts it as Ctrl+C does. */
    private static SessionLog runUntilTheChildStarted(Path projectDir, boolean continuousTesting) throws Exception {
        Path classes = Files.createDirectories(projectDir.resolve("target/classes"));
        Files.writeString(classes.resolve("marker.txt"), "built");
        Files.createDirectories(projectDir.resolve("src/main/java"));
        SessionLog log = new SessionLog();
        VidocqDevMojo mojo = new VidocqDevMojo();
        mojo.setLog(log);
        mojo.setProject(new MavenProject());
        mojo.setBaseDir(projectDir.toFile());
        mojo.setBuildDir(projectDir.resolve("target").toFile());
        mojo.setClassesDir(classes.toFile());
        mojo.setMainModule("no.such.module");
        mojo.setMainClass("no.such.Main");
        mojo.setAppLayer("classes");
        mojo.setLayerMode(true);
        mojo.setHotReload(true);
        mojo.setProfile("dev");
        mojo.setDevServices(false);
        mojo.setContinuousTesting(continuousTesting);
        mojo.setDebugOptions(false, 5005, false);
        mojo.setWatchDirs("src/main/java, ,src/main/resources");
        mojo.setTestWatchDirs("src/test/java,src/test/resources");

        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread session = Thread.ofPlatform().name("vidocq-dev-session-test").start(() -> {
            try {
                mojo.execute();
            } catch (Exception e) {
                failure.set(e);
            }
        });
        assertTrue(log.started.await(30, TimeUnit.SECONDS), () -> "the child never started: " + log.lines);
        session.interrupt();
        session.join(TimeUnit.SECONDS.toMillis(30));

        assertFalse(session.isAlive(), "the session did not end on interrupt");
        assertNull(failure.get(), () -> "the session failed: " + failure.get());
        return log;
    }

    /** Records the info and warning lines; thread-safe, the session logs on its own thread. */
    private static final class SessionLog extends SystemStreamLog {

        final List<String> lines = new CopyOnWriteArrayList<>();
        final CountDownLatch started = new CountDownLatch(1);

        @Override
        public void info(CharSequence content) {
            String line = "INFO " + content;
            lines.add(line);
            if (line.startsWith(STARTED)) {
                started.countDown();
            }
        }

        @Override
        public void warn(CharSequence content) {
            lines.add("WARN " + content);
        }
    }
}
