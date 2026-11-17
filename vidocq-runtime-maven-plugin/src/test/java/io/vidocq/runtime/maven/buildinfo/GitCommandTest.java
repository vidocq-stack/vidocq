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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code git} command never holds the build: a command that does not end in time is left out like a missing
 * one, and a process it leaves behind holding its output does not hold the build either (BUG-20261117-06).
 */
@DisabledOnOs(OS.WINDOWS)
class GitCommandTest {

    private static final Duration SHORT = Duration.ofMillis(300);

    @TempDir
    Path dir;

    @Test
    void aCommandThatAnswersGivesWhatItPrinted() throws IOException {
        Path answering = script("git-answering", "#!/bin/sh\necho 1762421400\n");

        assertEquals(Optional.of("1762421400\n"), GitCommand.output(new ProcessBuilder(answering.toString()), SHORT));
    }

    /** A command that hangs with its output open, as a git waiting on a lock would: the limit still holds. */
    @Test
    void aCommandThatDoesNotEndInTimeIsLeftOut() throws IOException {
        Path hanging = script("git-hanging", "#!/bin/sh\nexec sleep 10\n");

        assertEquals(Optional.empty(), GitCommand.output(new ProcessBuilder(hanging.toString()), SHORT));
    }

    /**
     * The command ended with 0, but a process it started still holds its output: the answer comes while that process
     * runs. Reading the output to its end before the wait, as before, waited for that process to end. What the answer
     * holds depends on the JDK: it collects what the command printed when the command exits
     * ({@code ProcessPipeInputStream.processExited}), unless the reading thread already waits in {@code read}, and the
     * reading then stops at the limit. Either answer is right.
     */
    @Test
    void aProcessTheCommandLeavesBehindDoesNotHoldTheBuild() throws IOException {
        Path pidFile = dir.resolve("leftover.pid");
        Path forking = script("git-forking", "#!/bin/sh\nsleep 60 &\necho $! > \"$1\"\necho 1762421400\nexit 0\n");

        Optional<String> answer = GitCommand.output(new ProcessBuilder(forking.toString(), pidFile.toString()), SHORT);
        Optional<ProcessHandle> leftover = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).strip()));
        try {
            assertTrue(leftover.map(ProcessHandle::isAlive).orElse(false),
                    "the answer came while the process the command left behind still ran");
            assertTrue(answer.isEmpty() || answer.get().equals("1762421400\n"), () -> "answer: " + answer);
        } finally {
            leftover.ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    /**
     * An interrupt during the wait leaves the command out and stays set for the build. The command closes its output
     * first, so the wait is reached while it runs, whichever way the output is read.
     */
    @Test
    void anInterruptedBuildLeavesTheCommandOutAndStaysInterrupted() throws IOException {
        Path hanging = script("git-hanging", "#!/bin/sh\nexec >&-\nexec sleep 10\n");

        Thread.currentThread().interrupt();
        try {
            assertEquals(Optional.empty(),
                    GitCommand.output(new ProcessBuilder(hanging.toString()), Duration.ofSeconds(30)));
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt is kept for the build");
        }
    }

    private Path script(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }
}
