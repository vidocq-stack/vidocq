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
package io.vidocq.runtime.cli.ext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the {@code maven-dependency-plugin:list} command that resolves a project's
 * runtime classpath, and parses the file it writes.
 *
 * <p>Each resolved line reads
 * {@code groupId:artifactId:type[:classifier]:version:scope:/absolute/file.jar}, optionally
 * followed by {@code  -- module name}. Maven 3.9 writes ANSI colours into that file
 * even with {@code -B}, so they are stripped first.</p>
 *
 * <p>Pure: no I/O, no process.</p>
 */
public final class DependencyList {

    /** Same version as {@code maven.dependency.plugin.version} in {@code vidocq-parent}. */
    public static final String PLUGIN_VERSION = "3.8.1";

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");
    private static final String MODULE_SUFFIX = " -- module ";
    private static final Set<String> SCOPES = Set.of("compile", "runtime", "provided", "system", "test");

    private DependencyList() {}

    /**
     * The command line resolving the runtime scope (compile + runtime, transitively)
     * of the project in the working directory into {@code outputFile}.
     */
    public static List<String> command(String executable, Path outputFile) {
        return List.of(
                executable,
                "-B",
                "-q",
                "org.apache.maven.plugins:maven-dependency-plugin:" + PLUGIN_VERSION + ":list",
                "-DincludeScope=runtime",
                "-DoutputAbsoluteArtifactFilename=true",
                "-DoutputFile=" + outputFile);
    }

    /** Parses the content of the file written by {@link #command(String, Path)}. */
    public static List<ResolvedArtifact> parse(String output) {
        List<ResolvedArtifact> result = new ArrayList<>();
        for (String raw : output.split("\\R")) {
            String line = ANSI.matcher(raw).replaceAll("").strip();
            int module = line.indexOf(MODULE_SUFFIX);
            if (module >= 0) {
                line = line.substring(0, module).strip();
            }
            ResolvedArtifact artifact = parseLine(line);
            if (artifact != null) {
                result.add(artifact);
            }
        }
        return result;
    }

    private static ResolvedArtifact parseLine(String line) {
        String[] parts = line.split(":");
        // groupId, artifactId, type, [classifier], version, scope, then the path
        // (which may itself contain ':' — a Windows drive letter).
        for (int scope = 4; scope <= 5 && scope < parts.length - 1; scope++) {
            if (SCOPES.contains(parts[scope])) {
                String file = String.join(":", List.of(parts).subList(scope + 1, parts.length));
                if (parts[0].isBlank() || parts[1].isBlank() || file.isBlank()) {
                    return null;
                }
                return new ResolvedArtifact(parts[0], parts[1], parts[scope - 1], Path.of(file));
            }
        }
        return null;
    }
}
