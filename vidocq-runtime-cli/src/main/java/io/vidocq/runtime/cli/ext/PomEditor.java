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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds or removes a project-level {@code <dependency>} block in a Maven POM,
 * working on the raw text so the rest of the file's formatting and comments are
 * preserved (a full DOM/StAX rewrite would reflow everything). Existing
 * dependencies are detected with {@link PomDependencies} (StAX) so the edits are
 * idempotent.
 *
 * <p>Pure: takes the POM text and returns a {@link Result}; no I/O.</p>
 */
public final class PomEditor {

    private PomEditor() {}

    /** Outcome of an edit: the (possibly unchanged) POM text and whether it changed. */
    public record Result(String pom, boolean changed) {}

    private static final Pattern GROUP_ID =
            Pattern.compile("<groupId>\\s*([^<\\s][^<]*?)\\s*</groupId>");
    private static final Pattern ARTIFACT_ID =
            Pattern.compile("<artifactId>\\s*([^<\\s][^<]*?)\\s*</artifactId>");

    /**
     * Inserts {@code coordinate} as a project dependency. Idempotent: if the
     * dependency is already declared, the POM is returned unchanged. If the POM
     * has no {@code <dependencies>} element, one is created before {@code </project>}.
     */
    public static Result add(String pom, ExtensionCoordinate coordinate) {
        if (PomDependencies.contains(pom, coordinate)) {
            return new Result(pom, false);
        }
        int close = pom.indexOf("</dependencies>");
        if (close >= 0) {
            int lineStart = pom.lastIndexOf('\n', close) + 1;
            String baseIndent = pom.substring(lineStart, close);
            String childIndent = isBlank(baseIndent) ? baseIndent + "    " : "        ";
            String block = coordinate.dependencyXml(childIndent) + "\n";
            return new Result(pom.substring(0, lineStart) + block + pom.substring(lineStart), true);
        }
        // No <dependencies> element — create one before </project>.
        int proj = pom.indexOf("</project>");
        if (proj < 0) {
            throw new IllegalArgumentException("Not a valid pom.xml: missing </project>.");
        }
        int lineStart = pom.lastIndexOf('\n', proj) + 1;
        String projIndent = pom.substring(lineStart, proj);
        String depsIndent = isBlank(projIndent) ? projIndent + "    " : "    ";
        String block = depsIndent + "<dependencies>\n"
                + coordinate.dependencyXml(depsIndent + "    ") + "\n"
                + depsIndent + "</dependencies>\n";
        return new Result(pom.substring(0, lineStart) + block + pom.substring(lineStart), true);
    }

    /**
     * Removes every project-level {@code <dependency>} block matching
     * {@code coordinate}. Returns the POM unchanged when the dependency is absent.
     */
    public static Result remove(String pom, ExtensionCoordinate coordinate) {
        if (!PomDependencies.contains(pom, coordinate)) {
            return new Result(pom, false);
        }
        int[] region = projectDependenciesRegion(pom);
        StringBuilder sb = new StringBuilder(pom);
        boolean changed = false;
        int search = region == null ? 0 : region[0];
        int limit = region == null ? sb.length() : region[1];

        int open;
        while ((open = sb.indexOf("<dependency>", search)) >= 0 && open < limit) {
            int closeTag = sb.indexOf("</dependency>", open);
            if (closeTag < 0) {
                break;
            }
            int blockEnd = closeTag + "</dependency>".length();
            String block = sb.substring(open, blockEnd);
            if (matches(block, coordinate)) {
                int removeStart = sb.lastIndexOf("\n", open) + 1;
                int removeEnd = blockEnd;
                if (removeEnd < sb.length() && sb.charAt(removeEnd) == '\n') {
                    removeEnd++;
                }
                sb.delete(removeStart, removeEnd);
                changed = true;
                int removedLen = removeEnd - removeStart;
                limit -= removedLen;
                search = removeStart;
            } else {
                search = blockEnd;
            }
        }
        return new Result(sb.toString(), changed);
    }

    private static boolean matches(String dependencyBlock, ExtensionCoordinate coordinate) {
        Matcher g = GROUP_ID.matcher(dependencyBlock);
        Matcher a = ARTIFACT_ID.matcher(dependencyBlock);
        if (!g.find() || !a.find()) {
            return false;
        }
        return coordinate.groupId().equals(g.group(1).trim())
                && coordinate.artifactId().equals(a.group(1).trim());
    }

    /**
     * Locates the project-level {@code <dependencies>...</dependencies>} region,
     * excluding a {@code <dependencyManagement>} block when present. Returns
     * {@code [start, endExclusive]} or {@code null} if no project dependencies
     * element exists (callers then scan the whole document).
     */
    private static int[] projectDependenciesRegion(String pom) {
        int dmStart = pom.indexOf("<dependencyManagement>");
        int dmEnd = dmStart < 0 ? -1 : pom.indexOf("</dependencyManagement>", dmStart);
        int from = 0;
        int deps;
        while ((deps = pom.indexOf("<dependencies>", from)) >= 0) {
            boolean insideDm = dmStart >= 0 && dmEnd >= 0 && deps > dmStart && deps < dmEnd;
            if (!insideDm) {
                int end = pom.indexOf("</dependencies>", deps);
                return end < 0 ? null : new int[]{deps, end + "</dependencies>".length()};
            }
            from = deps + "<dependencies>".length();
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s.chars().allMatch(Character::isWhitespace);
    }
}
