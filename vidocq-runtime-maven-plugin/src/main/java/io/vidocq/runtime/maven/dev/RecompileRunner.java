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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives an out-of-process {@code mvn process-classes} run, used by
 * {@code vidocq:dev} to replay javac+APT (Vauban, Cassini, Mansart) and
 * {@code vidocq:generate} whenever a source file changes.
 *
 * <p>We deliberately <b>shell-out</b> rather than embedding Maven: Maven 4's
 * embedded API is still unstable, and a clean external invocation gives us
 * deterministic behavior and proper isolation from the plugin classpath.</p>
 *
 * <p>The runner inherits stdout/stderr so the user sees compiler errors in the
 * same terminal as the dev-mode banner.</p>
 */
final class RecompileRunner {

    private final Path projectDir;
    private final String mavenExecutable;

    RecompileRunner(Path projectDir) {
        this(projectDir, detectMavenExecutable(projectDir));
    }

    RecompileRunner(Path projectDir, String mavenExecutable) {
        this.projectDir = projectDir;
        this.mavenExecutable = mavenExecutable;
    }

    /**
     * Run {@code mvn process-classes -DskipTests} in the project directory and
     * return the process exit code. {@code 0} means compilation succeeded.
     */
    int run() throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(mavenExecutable);
        cmd.add("-ntp");
        cmd.add("-q");
        cmd.add("process-classes");
        cmd.add("-DskipTests");

        Process p = new ProcessBuilder(cmd)
                .directory(projectDir.toFile())
                .redirectErrorStream(true)
                .inheritIO()
                .start();
        return p.waitFor();
    }

    /**
     * Resolve which {@code mvn} binary to invoke. Preference order matches what
     * a developer would naturally type at the prompt:
     * <ol>
     *   <li>{@code ./mvnw} (or {@code mvnw.cmd} on Windows) in the project dir
     *       <i>or any parent directory</i> — Vidocq sub-projects sit under a
     *       workspace whose {@code mvnw} lives at the workspace root, not in
     *       each module. Walking the parents guarantees we pick the Maven 4
     *       wrapper pinned in {@code .sdkmanrc}, not whatever {@code mvn} is
     *       on the user's {@code PATH} (often Maven 3, which cannot parse
     *       Model 4.1.0 POMs);</li>
     *   <li>fallback to {@code mvn} on the {@code PATH}.</li>
     * </ol>
     */
    static String detectMavenExecutable(Path projectDir) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String wrapperName = windows ? "mvnw.cmd" : "mvnw";
        for (Path dir = projectDir; dir != null; dir = dir.getParent()) {
            Path wrapper = dir.resolve(wrapperName);
            if (Files.isExecutable(wrapper)) {
                return wrapper.toAbsolutePath().toString();
            }
        }
        return windows ? "mvn.cmd" : "mvn";
    }
}
