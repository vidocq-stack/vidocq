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
package io.vidocq.runtime.cli.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Thin, impure bridge between the pure {@link MavenInvocation} command list and
 * a real Maven process. Resolves the executable (a project {@code mvnw} wrapper
 * when present, otherwise {@code mvn} on the {@code PATH}) and runs it with the
 * caller's stdio inherited.
 */
public final class MavenLauncher {

    private MavenLauncher() {}

    /** {@code true} when running on Windows (selects {@code mvnw.cmd}). */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** The Maven wrapper file name for the platform. */
    public static String wrapperName(boolean windows) {
        return windows ? "mvnw.cmd" : "mvnw";
    }

    /**
     * Resolve the Maven executable to use from {@code start}.
     *
     * <p>Walks {@code start} and its ancestors looking for the platform wrapper;
     * returns its absolute path when found, otherwise falls back to {@code mvn}.
     */
    public static String resolveExecutable(Path start, boolean windows) {
        String wrapper = wrapperName(windows);
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(wrapper);
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return windows ? "mvn.cmd" : "mvn";
    }

    /** Resolve the Maven executable for the current platform from {@code start}. */
    public static String resolveExecutable(Path start) {
        return resolveExecutable(start, isWindows());
    }

    /**
     * Run a Maven command in {@code workingDir}, inheriting stdio so Maven's
     * own (coloured) output streams straight to the terminal.
     *
     * @return Maven's exit code, or {@code 1} if the process could not start
     */
    public static int run(List<String> command, Path workingDir) {
        ProcessBuilder pb = new ProcessBuilder(command).inheritIO();
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        try {
            return pb.start().waitFor();
        } catch (IOException e) {
            System.err.println("Failed to launch Maven (" + command.get(0) + "): " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Build interrupted.");
            return 1;
        }
    }
}
