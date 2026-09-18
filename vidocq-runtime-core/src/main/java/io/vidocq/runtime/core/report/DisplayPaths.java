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
package io.vidocq.runtime.core.report;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * How the startup report writes a file: the summary report, the default of a production launch, never
 * prints a directory; the detailed one prints a path relative to the working directory, or under
 * {@code ~} for the home directory, its middle names elided when it is long:
 * {@code ~/.m2/.../langchain4j-cdi-mcp-server-1.4.0-SNAPSHOT.jar}.
 *
 * @param workingDirectory the working directory ({@code user.dir}), or {@code null}
 * @param home             the home directory ({@code user.home}), or {@code null}
 */
public record DisplayPaths(Path workingDirectory, Path home) {

    /** A path of more names than this keeps its first two and its last one. */
    static final int MAX_NAMES = 4;
    /** What stands for the names left out. */
    static final String ELIDED = "...";

    public DisplayPaths {
        workingDirectory = workingDirectory == null ? null : workingDirectory.normalize();
        home = home == null ? null : home.normalize();
    }

    /** The working directory and the home directory of this JVM. */
    public static DisplayPaths current() {
        return new DisplayPaths(path(System.getProperty("user.dir")), path(System.getProperty("user.home")));
    }

    /**
     * The file name alone, after {@code .../} when the path has directories:
     * {@code /opt/acme/app} gives {@code .../app}.
     */
    public String summary(String file) {
        Path path = path(file);
        if (path == null || path.getFileName() == null) {
            return file;
        }
        String name = path.getFileName().toString();
        return path.getNameCount() > 1 || path.isAbsolute() ? ELIDED + separator(path) + name : name;
    }

    /**
     * The path relative to the working directory, or under {@code ~}, or absolute when it is in neither;
     * the names past the second one and before the last one are elided when there are more than
     * {@value #MAX_NAMES}.
     */
    public String detailed(String file) {
        Path path = path(file);
        if (path == null) {
            return file;
        }
        if (!path.isAbsolute()) {
            return join(null, path);
        }
        Path normalized = path.normalize();
        if (usable(workingDirectory) && normalized.startsWith(workingDirectory)) {
            Path relative = workingDirectory.relativize(normalized);
            return relative.toString().isEmpty() ? "." : join(null, relative);
        }
        if (usable(home) && normalized.startsWith(home)) {
            Path relative = home.relativize(normalized);
            return relative.toString().isEmpty() ? "~" : join("~", relative);
        }
        Path root = normalized.getRoot();
        return root == null ? join(null, normalized) : root + join(null, root.relativize(normalized));
    }

    /**
     * {@code text} with the files it names under the working directory made relative, and those under the
     * home directory written under {@code ~}: for a name that embeds a path, such as a configuration source.
     */
    public String scrub(String text) {
        if (text == null) {
            return null;
        }
        String scrubbed = text;
        if (usable(workingDirectory)) {
            scrubbed = scrubbed.replace(workingDirectory + separator(workingDirectory), "");
        }
        if (usable(home)) {
            scrubbed = scrubbed.replace(home + separator(home), "~" + separator(home));
        }
        return scrubbed;
    }

    private static String join(String lead, Path relative) {
        List<String> segments = new ArrayList<>();
        if (lead != null) {
            segments.add(lead);
        }
        for (Path name : relative) {
            segments.add(name.toString());
        }
        if (segments.size() > MAX_NAMES) {
            segments = List.of(segments.get(0), segments.get(1), ELIDED, segments.getLast());
        }
        return String.join(separator(relative), segments);
    }

    /** An absolute directory that is not a root: a root would make every absolute path relative. */
    private static boolean usable(Path directory) {
        return directory != null && directory.isAbsolute() && directory.getNameCount() > 0;
    }

    private static String separator(Path path) {
        return path.getFileSystem().getSeparator();
    }

    private static Path path(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value);
        } catch (RuntimeException invalid) {
            return null;
        }
    }
}
