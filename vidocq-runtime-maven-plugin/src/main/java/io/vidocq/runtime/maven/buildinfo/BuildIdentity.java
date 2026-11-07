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
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * The identity of a build of an application, what {@code vidocq:build-info} writes into
 * {@code META-INF/vidocq/build-info/<artifactId>.properties} and the runtime's {@code BuildInfo} reads: the keys
 * {@code git-commit-id-maven-plugin} wrote under {@code vidocq-runtime-parent}, plus the full commit and the branch
 * (Vidocq/vidocq#380).
 *
 * <p>Outside any git repository the build does not fail: the commit and the branch read {@value #UNVERSIONED}, and
 * the build time is still written.</p>
 *
 * @param version the project version
 * @param commit  the full commit hash, or {@value #UNVERSIONED}
 * @param branch  the branch, {@code HEAD} when detached, or {@value #UNVERSIONED}
 * @param dirty   whether tracked files were modified, {@code null} when git could not tell
 * @param builtAt the build time, to the second
 */
public record BuildIdentity(String version, String commit, String branch, Boolean dirty, Instant builtAt) {

    /** The commit of a build no repository names; the same word the runtime shows for a version it cannot find. */
    public static final String UNVERSIONED = "unversionned";

    /** The length of {@code git.commit.id.abbrev}, as vidocq-runtime-parent configures git-commit-id-maven-plugin. */
    static final int ABBREVIATION = 8;

    /** What {@code git rev-parse --abbrev-ref HEAD} prints for a detached HEAD. */
    private static final String DETACHED = "HEAD";

    /** The identity of a build of the project in {@code dir}, built at {@code builtAt}; {@code status} tells dirty. */
    public static BuildIdentity collect(Path dir, String version, Instant builtAt, GitStatus status) {
        Optional<GitHead> head = GitHead.read(dir);
        if (head.isEmpty()) {
            return new BuildIdentity(version, UNVERSIONED, UNVERSIONED, null, builtAt);
        }
        String branch = head.get().branch() == null ? DETACHED : head.get().branch();
        return new BuildIdentity(version, head.get().commit(), branch, status.dirty(dir).orElse(null), builtAt);
    }

    /**
     * The build time: {@code project.build.outputTimestamp} when the pom sets one, as an ISO-8601 date-time or epoch
     * seconds, so that a reproducible build stays reproducible; otherwise now, to the second.
     */
    public static Instant buildTime(String outputTimestamp, Clock clock) {
        if (outputTimestamp != null && !outputTimestamp.isBlank()) {
            String value = outputTimestamp.strip();
            try {
                return OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.SECONDS);
            } catch (DateTimeParseException _) {
                if (value.matches("\\d{1,18}")) {
                    return Instant.ofEpochSecond(Long.parseLong(value));
                }
            }
        }
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    /** Whether no repository names this build. */
    public boolean isUnversioned() {
        return UNVERSIONED.equals(commit);
    }

    /** The first {@value #ABBREVIATION} characters of the commit, or {@value #UNVERSIONED}. */
    public String abbreviatedCommit() {
        return isUnversioned() || commit.length() <= ABBREVIATION ? commit : commit.substring(0, ABBREVIATION);
    }

    /**
     * The content of the properties file: sorted keys, no comment line, so that two builds of the same commit at the
     * same time write the same bytes. {@code git.dirty} is left out when git could not tell.
     */
    public String properties() {
        StringBuilder out = new StringBuilder();
        line(out, "git.branch", branch);
        line(out, "git.build.time", DateTimeFormatter.ISO_INSTANT.format(builtAt.truncatedTo(ChronoUnit.SECONDS)));
        line(out, "git.build.version", version);
        line(out, "git.commit.id.abbrev", abbreviatedCommit());
        line(out, "git.commit.id.full", commit);
        if (dirty != null) {
            line(out, "git.dirty", dirty.toString());
        }
        return out.toString();
    }

    /** One {@code key=value} line, the value escaped as {@code java.util.Properties} reads it (ISO-8859-1). */
    private static void line(StringBuilder out, String key, String value) {
        out.append(key).append('=');
        for (char c : value.toCharArray()) {
            appendEscaped(out, c);
        }
        out.append('\n');
    }

    /** Backslash, line breaks and tabs escaped; a character outside printable ASCII as {@code \\uXXXX}. */
    private static void appendEscaped(StringBuilder out, char c) {
        switch (c) {
            case '\\' -> out.append("\\\\");
            case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");
            case '\t' -> out.append("\\t");
            default -> {
                if (c < 0x20 || c > 0x7e) {
                    out.append(String.format("\\u%04x", (int) c));
                } else {
                    out.append(c);
                }
            }
        }
    }
}
