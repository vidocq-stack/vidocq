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

import io.vidocq.runtime.devservices.host.DevServicesFlag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * One Surefire run as a subprocess (spec §2.3): {@code mvn process-test-classes surefire:test}, offline and quiet,
 * so the tests run exactly as {@code mvn test} runs them — the same module path, {@code argLine} and JUnit launcher
 * listeners, and the runtime dependencies {@code vidocq:test-path} names at {@code process-test-classes} for the
 * application {@code @VidocqTest} starts (BUG-20261030-06). Its output goes to the log file, overwritten per run; the stale {@code TEST-*.xml} are deleted first,
 * so that the reports found afterwards are this run's. The command line is never logged: it may hold a dev
 * service's password.
 *
 * <p>A run that misses an artifact offline runs again online, once, with the same tests: {@code vidocq:dev} resolves
 * only the runtime dependencies, so on a new project, or after a test dependency was added, the test-scope jars and
 * Surefire's JUnit provider may never have been downloaded (BUG-20261106-01). The log is then the online run's.
 */
final class TestRunner implements ContinuousTesting.Launcher {

    /** How long a cancelled run may take to stop before it is killed. */
    static final Duration GRACE = Duration.ofSeconds(10);

    private final Path projectDir;
    private final String maven;
    private final TestServices services;
    private final Path logFile;
    private final Path reportsDir;

    /**
     * @param maven    the Maven executable, as {@link RecompileRunner#detectMavenExecutable} finds it
     * @param services the dev services of the runs, whose keys each run gets as {@code -D}, in their order
     */
    TestRunner(Path projectDir, String maven, TestServices services, Path logFile, Path reportsDir) {
        this.projectDir = projectDir;
        this.maven = maven;
        this.services = services;
        this.logFile = logFile;
        this.reportsDir = reportsDir;
    }

    /**
     * The command (spec §2.3), offline ({@code -o}) unless it runs again after an offline miss. {@code
     * vidocq.dev.devServices=false} comes last and a key of that name in {@code properties} is dropped: the test JVM
     * never starts containers of its own, since the plugin JVM hosts them, once for every run (BUG-20261114-08).
     */
    static List<String> command(String maven, List<String> tests, Map<String, String> properties, boolean offline) {
        List<String> cmd = new ArrayList<>(List.of(maven, "-ntp"));
        if (offline) {
            cmd.add("-o");
        }
        cmd.addAll(List.of("-q", "process-test-classes", "surefire:test",
                "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false"));
        if (!tests.isEmpty()) {
            cmd.add("-Dtest=" + String.join(",", tests));
        }
        properties.forEach((key, value) -> {
            if (!DevServicesFlag.KEY.equals(key)) {
                cmd.add("-D" + key + "=" + value);
            }
        });
        cmd.add("-D" + DevServicesFlag.KEY + "=false");
        return cmd;
    }

    /** Readies the dev services of the run: under {@code vidocq:dev}, the tests' own, at the first run. */
    @Override
    public void prepare() {
        services.prepare();
    }

    /** The run gets the keys of its dev services as they are when it starts. */
    @Override
    public ContinuousTesting.Launched launch(List<String> tests) throws IOException {
        deleteReports(reportsDir);
        Files.createDirectories(logFile.toAbsolutePath().getParent());
        Map<String, String> keys = services.keys();
        Process offline = start(command(maven, tests, keys, true));
        return new Running(offline, logFile, () -> start(command(maven, tests, keys, false)));
    }

    /** Stops the dev services of the runs, once the last run ended. */
    @Override
    public void close() {
        services.close();
    }

    /** Starts {@code command} in the project, its output overwriting the log. */
    private Process start(List<String> command) throws IOException {
        return new ProcessBuilder(command)
                .directory(projectDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
    }

    /**
     * Whether the log of a run shows Maven missing an artifact it may not download offline, in either of the ways
     * Maven says it, as opposed to any other failure (BUG-20261106-01). {@code false} when the log cannot be read.
     */
    static boolean missedOffline(Path logFile) {
        try {
            String log = Files.readString(logFile, StandardCharsets.UTF_8);
            return log.contains(" in offline mode and the artifact ") || log.contains("repository system is offline");
        } catch (IOException | UncheckedIOException _) {
            return false;
        }
    }

    /**
     * Whether the log of a run shows a compiler failure — Maven's {@code COMPILATION ERROR} block, or a
     * {@code Compilation failure} of the compiler plugin — as opposed to any other failure before the tests, such as
     * a Surefire provider missing offline (#138). {@code false} when the log cannot be read.
     */
    static boolean compilationFailed(Path logFile) {
        try {
            String log = Files.readString(logFile, StandardCharsets.UTF_8);
            return log.contains("COMPILATION ERROR") || log.contains("Compilation failure");
        } catch (IOException | UncheckedIOException _) {
            return false;
        }
    }

    /** Deletes the {@code TEST-*.xml} of {@code dir}, and nothing else. */
    static void deleteReports(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> listing = Files.list(dir)) {
            for (Path report : listing.filter(SurefireReports::isReport).toList()) {
                Files.deleteIfExists(report);
            }
        }
    }

    /**
     * Destroys {@code root} and every process it started (spec §2.3). The descendants are listed first, while they
     * are still attached to it, then each gets a {@code SIGTERM}. Only {@code root}, which this JVM started and
     * reaps, is waited for, {@code grace} at most. Whatever is still alive afterwards gets a {@code SIGKILL} at once:
     * a descendant that ignores {@code SIGTERM}, or a zombie that a container's PID 1 never reaps, must not hold up
     * the reload loop for the whole grace period (#138).
     */
    static void destroyTree(ProcessHandle root, Duration grace) {
        List<ProcessHandle> descendants = root.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        root.destroy();
        try {
            root.onExit().get(grace.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException | ExecutionException _) {
            // killed below
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        if (root.isAlive()) {
            root.destroyForcibly();
        }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    /** Starts the online run of a run that missed an artifact offline. */
    @FunctionalInterface
    private interface OnlineRun {
        Process start() throws IOException;
    }

    /**
     * The offline run, then the online one when the offline run missed an artifact. Until {@link #waitFor} has taken
     * that decision, the run has not ended: a cancel that comes after the offline process exited, but before the
     * online one starts, stops the run, and no online run starts.
     */
    private static final class Running implements ContinuousTesting.Launched {

        private final Object lock = new Object();
        private final Path logFile;
        /** Guarded by lock. */
        private Process process;
        /** Guarded by lock: the online run still to start after an offline miss, {@code null} once decided. */
        private OnlineRun online;
        /** Guarded by lock. */
        private boolean cancelled;

        Running(Process offline, Path logFile, OnlineRun online) {
            this.process = offline;
            this.logFile = logFile;
            this.online = online;
        }

        @Override
        public int waitFor() throws InterruptedException {
            Process first;
            synchronized (lock) {
                first = process;
            }
            int exit = first.waitFor();
            Process again;
            synchronized (lock) {
                OnlineRun start = !cancelled && runsAgain(first) ? online : null;
                online = null;
                if (start == null) {
                    return exit;
                }
                try {
                    process = start.start();
                } catch (IOException _) {
                    return exit;
                }
                again = process;
            }
            return again.waitFor();
        }

        /** Guarded by lock: whether {@code ended}, the offline process, missed an artifact and runs again online. */
        private boolean runsAgain(Process ended) {
            return online != null && ended.exitValue() != 0 && missedOffline(logFile);
        }

        @Override
        public void cancel() {
            Process stopping;
            synchronized (lock) {
                stopping = process;
                if (!stopping.isAlive() && !runsAgain(stopping)) {
                    return;
                }
                cancelled = true;
            }
            destroyTree(stopping.toHandle(), GRACE);
        }

        @Override
        public boolean cancelled() {
            synchronized (lock) {
                return cancelled;
            }
        }
    }
}
