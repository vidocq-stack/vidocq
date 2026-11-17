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
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a {@code git} command for the build identity and reads what it printed: nothing when the command is missing,
 * fails or does not return within {@value GitStatus#TIMEOUT_SECONDS} seconds, so that the build never depends on it.
 */
final class GitCommand {

    private GitCommand() {
    }

    /** What {@code command} printed on its standard output, once it ended with 0. */
    static Optional<String> output(ProcessBuilder command) {
        return output(command, Duration.ofSeconds(GitStatus.TIMEOUT_SECONDS));
    }

    /**
     * What {@code command} printed on its standard output, once it ended with 0 within {@code timeout}. The output is
     * read while the command runs, so a command that hangs with its output open is stopped at {@code timeout}, and a
     * process it leaves behind holding its output does not hold the build either: the reading stops at the limit.
     */
    static Optional<String> output(ProcessBuilder command, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Process process;
        try {
            process = command.redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException _) {
            return Optional.empty();
        }
        FutureTask<byte[]> output = new FutureTask<>(() -> {
            try (InputStream in = process.getInputStream()) {
                return in.readAllBytes();
            }
        });
        Thread.ofVirtual().name("vidocq-build-info-git").start(output);
        try {
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS) || process.exitValue() != 0) {
                return Optional.empty();
            }
            byte[] printed = output.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return Optional.of(new String(printed, StandardCharsets.UTF_8));
        } catch (ExecutionException | TimeoutException _) {
            return Optional.empty();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            process.destroyForcibly();
        }
    }
}
