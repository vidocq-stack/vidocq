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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * One Surefire run as a subprocess (spec §2.3): {@code mvn test-compile surefire:test}, offline and quiet, so the
 * tests run exactly as {@code mvn test} runs them — the same module path, {@code argLine} and JUnit launcher
 * listeners. Its output goes to the log file, overwritten per run; the stale {@code TEST-*.xml} are deleted first,
 * so that the reports found afterwards are this run's. The command line is never logged: it may hold a dev
 * service's password.
 */
final class TestRunner implements ContinuousTesting.Launcher {

    /** How long a cancelled run may take to stop before it is killed. */
    static final Duration GRACE = Duration.ofSeconds(10);

    private final Path projectDir;
    private final String maven;
    private final Map<String, String> properties;
    private final Path logFile;
    private final Path reportsDir;

    /**
     * @param maven      the Maven executable, as {@link RecompileRunner#detectMavenExecutable} finds it
     * @param properties the dev session's keys, passed as {@code -D} in this order
     */
    TestRunner(Path projectDir, String maven, Map<String, String> properties, Path logFile, Path reportsDir) {
        this.projectDir = projectDir;
        this.maven = maven;
        this.properties = new LinkedHashMap<>(properties);
        this.logFile = logFile;
        this.reportsDir = reportsDir;
    }

    /**
     * The command (spec §2.3). {@code vidocq.dev.devServices=false} comes last and a key of that name in
     * {@code properties} is dropped: the tests use the dev session's containers, and never start their own.
     */
    static List<String> command(String maven, List<String> tests, Map<String, String> properties) {
        List<String> cmd = new ArrayList<>(List.of(maven, "-ntp", "-o", "-q", "test-compile", "surefire:test",
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

    @Override
    public ContinuousTesting.Launched launch(List<String> tests) throws IOException {
        deleteReports(reportsDir);
        Files.createDirectories(logFile.toAbsolutePath().getParent());
        Process process = new ProcessBuilder(command(maven, tests, properties))
                .directory(projectDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
        return new Running(process);
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
     * Destroys {@code root} and every process it started (spec §2.3): the descendants are listed first, while they
     * are still attached to it, then each gets a {@code SIGTERM}, and whatever is alive after {@code grace} a
     * {@code SIGKILL}.
     */
    static void destroyTree(ProcessHandle root, Duration grace) {
        List<ProcessHandle> tree = new ArrayList<>(root.descendants().toList());
        tree.add(root);
        tree.forEach(ProcessHandle::destroy);
        long deadline = System.nanoTime() + grace.toNanos();
        for (ProcessHandle handle : tree) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                break;
            }
            try {
                handle.onExit().get(left, TimeUnit.NANOSECONDS);
            } catch (TimeoutException | ExecutionException stillThere) {
                // killed below
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    private record Running(Process process) implements ContinuousTesting.Launched {

        @Override
        public int waitFor() throws InterruptedException {
            return process.waitFor();
        }

        @Override
        public void cancel() {
            destroyTree(process.toHandle(), GRACE);
        }
    }
}
