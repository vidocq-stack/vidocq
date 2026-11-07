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
package io.vidocq.runtime.maven.buildinfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Whether a working tree has uncommitted changes to tracked files. Only the {@code git} command can tell cheaply:
 * {@link #command} runs {@code git status --porcelain --untracked-files=no} and answers nothing when the command is
 * missing, fails or does not return in time, so that the build never depends on it.
 */
@FunctionalInterface
public interface GitStatus {

    /** Never asks: the dirty flag is left out. */
    GitStatus NONE = dir -> Optional.empty();

    /** How long the command may take before the flag is left out. */
    long TIMEOUT_SECONDS = 10;

    /** {@code true} when tracked files are modified, {@code false} when clean, empty when it cannot be told. */
    Optional<Boolean> dirty(Path dir);

    /** The {@code git} command on the PATH, as the build runs it. */
    static GitStatus command() {
        return running(dir -> new ProcessBuilder("git", "-C", dir.toAbsolutePath().toString(), "status",
                "--porcelain", "--untracked-files=no"));
    }

    /**
     * Runs the process {@code command} builds for a directory and reads its answer: dirty when it writes something,
     * clean when it writes nothing and exits with 0, nothing when it is missing, fails or does not return in time.
     */
    static GitStatus running(Function<Path, ProcessBuilder> command) {
        return dir -> {
            try {
                Process process = command.apply(dir)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                byte[] output;
                try (InputStream in = process.getInputStream()) {
                    output = in.readAllBytes();
                }
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return Optional.empty();
                }
                if (process.exitValue() != 0) {
                    return Optional.empty();
                }
                return Optional.of(!new String(output, StandardCharsets.UTF_8).isBlank());
            } catch (IOException _) {
                return Optional.empty();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        };
    }
}
