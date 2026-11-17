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

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * When a commit was made: its committer date, what {@code git-commit-id-maven-plugin} writes as
 * {@code git.commit.time}. The commit object sits compressed or packed in {@code .git}, so only the {@code git} command
 * reads it cheaply: {@link #command} runs {@code git show -s --format=%ct <commit>} and answers nothing when the
 * command is missing, fails or does not return in time, so that the build never depends on it (BUG-20261117-06).
 */
@FunctionalInterface
public interface GitCommitTime {

    /** Never asks: the commit time is left out. */
    GitCommitTime NONE = (dir, commit) -> Optional.empty();

    /** When {@code commit} was made, read in the working tree {@code dir}; empty when it cannot be told. */
    Optional<Instant> of(Path dir, String commit);

    /** The {@code git} command on the PATH, as the build runs it; a signature the configuration shows is not read. */
    static GitCommitTime command() {
        return running((dir, commit) -> new ProcessBuilder("git", "-C", dir.toAbsolutePath().toString(),
                "-c", "log.showSignature=false", "show", "-s", "--format=%ct", commit));
    }

    /**
     * Runs the process {@code command} builds for a directory and a commit, and reads its answer, epoch seconds:
     * nothing when it is missing, fails, does not return in time or prints anything else.
     */
    static GitCommitTime running(BiFunction<Path, String, ProcessBuilder> command) {
        return (dir, commit) -> GitCommand.output(command.apply(dir, commit))
                .map(String::strip)
                .filter(seconds -> seconds.matches("\\d{1,18}"))
                .map(seconds -> Instant.ofEpochSecond(Long.parseLong(seconds)));
    }
}
