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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Forked child JVM launched exactly like the production runtime — strict
 * module-path, no classpath. The dev mode kills and respawns this process on
 * every accepted source changes (Approach A in {@code DEBUGMODE.md}).
 *
 * <p>{@link #stop(Duration)} sends a SIGTERM-equivalent ({@link Process#destroy()})
 * and waits for the screed drain to complete. If the child has not exited after
 * the grace period, {@link Process#destroyForcibly()} takes over.</p>
 */
final class ChildJvm {

    /** Java executable, resolved once from {@code java.home}. */
    private static final String JAVA_BIN = resolveJavaBin();

    private final ProcessBuilder builder;
    private Process process;

    private ChildJvm(ProcessBuilder builder) {
        this.builder = builder;
    }

    /**
     * Builder helper — produces a ChildJvm bound to the given module path /
     * main module / extra JVM args. The {@code workingDir} is the project base
     * dir, so the child sees the user's {@code application.properties} /
     * {@code vidocq.properties} the same way a {@code mvn exec:java} would.
     *
     * <p>Two launch shapes:
     * <ul>
     *   <li>{@code appPath} non-empty — <b>universal-loader mode</b>: the application
     *       archives stay off the module path and are handed to the runtime through
     *       {@code -Dvidocq.app.path} (child module layer defined by the Vauban class
     *       loader); the root module is always the runtime, and {@code mainClass} (when
     *       given) becomes {@code -Dvidocq.app.main}, run through the layer.</li>
     *   <li>{@code appPath} null/empty — legacy shape: everything on the module path,
     *       {@code --module mainModule[/mainClass]}.</li>
     * </ul>
     */
    static ChildJvm of(List<Path> modulePath,
                       List<Path> appPath,
                       String mainModule,
                       String mainClass,
                       List<String> extraJvmArgs,
                       Map<String, String> systemProps,
                       Path workingDir) {
        boolean layerMode = appPath != null && !appPath.isEmpty();
        List<String> command = new ArrayList<>();
        command.add(JAVA_BIN);

        // System properties — at minimum -Dvidocq.profile=dev (caller injects it).
        for (Map.Entry<String, String> e : systemProps.entrySet()) {
            command.add("-D" + e.getKey() + "=" + e.getValue());
        }
        if (layerMode) {
            command.add("-Dvidocq.app.path=" + joinPath(appPath));
            if (mainClass != null && !mainClass.isBlank()) {
                command.add("-Dvidocq.app.main=" + mainClass);
            }
        }

        if (extraJvmArgs != null) {
            command.addAll(extraJvmArgs);
        }

        command.add("--module-path");
        command.add(joinPath(modulePath));
        // Resolve every module on the path, not only the main module's `requires` closure — utility
        // modules loaded reflectively (JDBC drivers, Flyway → Jackson, …) are otherwise absent from the
        // module graph and fail at runtime. Mirrors the production launcher (Dockerfile / jlink image).
        command.add("--add-modules");
        command.add("ALL-MODULE-PATH");
        command.add("--module");
        String moduleRef;
        if (layerMode) {
            moduleRef = "io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq";
        } else {
            moduleRef = (mainClass == null || mainClass.isBlank())
                    ? mainModule
                    : mainModule + "/" + mainClass;
        }
        command.add(moduleRef);

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .inheritIO();
        return new ChildJvm(pb);
    }

    /** The assembled command line (tests). */
    List<String> command() {
        return List.copyOf(builder.command());
    }

    /** Start the child process. Returns the spawned PID for logging. */
    long start() throws IOException {
        process = builder.start();
        return process.pid();
    }

    boolean isAlive() {
        return process != null && process.isAlive();
    }

    /**
     * Send a graceful termination signal and wait up to {@code grace} for the
     * child to exit. If it is still alive when the deadline passes, force-kill
     * it. This ordering matches what the user gets from Ctrl+C in a normal
     * Vidocq run — chappe's shutdown hook drains in-flight connections.
     */
    void stop(Duration grace) throws InterruptedException {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        boolean exited = process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static String joinPath(List<Path> entries) {
        String sep = System.getProperty("path.separator");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(entries.get(i).toString());
        }
        return sb.toString();
    }

    private static String resolveJavaBin() {
        String home = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return home + "/bin/" + (windows ? "java.exe" : "java");
    }
}
