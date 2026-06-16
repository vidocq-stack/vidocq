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

import java.util.ArrayList;
import java.util.List;

/**
 * Pure assembly of a Maven command line. Given an executable, the phases/goals
 * to run and a set of options, it produces the ordered argument list that
 * {@link MavenLauncher} feeds to {@link ProcessBuilder}.
 *
 * <p>Kept side-effect free so it can be unit-tested without spawning Maven.
 */
public final class MavenInvocation {

    private MavenInvocation() {}

    /** Toggles that influence the generated Maven command line. */
    public record Options(boolean offline, boolean skipTests, List<String> passthrough) {

        public Options {
            passthrough = passthrough == null ? List.of() : List.copyOf(passthrough);
        }

        /** No flags, no extra arguments. */
        public static Options none() {
            return new Options(false, false, List.of());
        }
    }

    /**
     * Build the full command line: {@code <executable> [-o] [-DskipTests]
     * <goals...> [passthrough...]}.
     *
     * @param executable the Maven executable (a {@code mvnw} path or {@code mvn})
     * @param goals      the lifecycle phases / plugin goals to run, in order
     * @param options    the flags and pass-through arguments
     * @return an immutable, ordered argument list
     */
    public static List<String> command(String executable, List<String> goals, Options options) {
        if (executable == null || executable.isBlank()) {
            throw new IllegalArgumentException("executable must not be blank");
        }
        if (goals == null || goals.isEmpty()) {
            throw new IllegalArgumentException("at least one goal/phase is required");
        }
        Options opts = options == null ? Options.none() : options;

        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        if (opts.offline()) {
            cmd.add("-o");
        }
        if (opts.skipTests()) {
            cmd.add("-DskipTests");
        }
        cmd.addAll(goals);
        cmd.addAll(opts.passthrough());
        return List.copyOf(cmd);
    }
}
