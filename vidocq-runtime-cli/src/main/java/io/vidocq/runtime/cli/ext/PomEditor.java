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

import java.util.ArrayList;
import java.util.List;
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
        return add(pom, coordinate, null);
    }

    /**
     * {@link #add(String, ExtensionCoordinate)} pinning {@code version} (none when
     * {@code null}). The runtime parent manages Vidocq artifacts at {@code ${project.version}},
     * which Maven resolves to the application's own version, so an added extension must
     * name the runtime version itself.
     */
    public static Result add(String pom, ExtensionCoordinate coordinate, String version) {
        if (PomDependencies.contains(pom, coordinate)) {
            return new Result(pom, false);
        }
        int close = pom.indexOf("</dependencies>");
        if (close >= 0) {
            int lineStart = pom.lastIndexOf('\n', close) + 1;
            String baseIndent = pom.substring(lineStart, close);
            String childIndent = isBlank(baseIndent) ? baseIndent + "    " : "        ";
            String block = coordinate.dependencyXml(childIndent, version) + "\n";
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
                + coordinate.dependencyXml(depsIndent + "    ", version) + "\n"
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

    private static final String APT_OPEN = "<annotationProcessorPaths";
    private static final String APT_CLOSE = "</annotationProcessorPaths>";
    private static final String APPENDING_APT_OPEN = "<annotationProcessorPaths combine.children=\"append\">";
    private static final String COMPILER_ARTIFACT = "<artifactId>maven-compiler-plugin</artifactId>";

    /**
     * Wires an extension's APT codegen bundle into the compiler plugin's
     * {@code annotationProcessorPaths} — {@code vidocq:checkpom} fails the build
     * when the matching extension is on the dependencies without it. Idempotent.
     * The path pins {@code version}, the runtime version: {@code ${project.version}}
     * would be the application's own.
     *
     * <p>Only the project build is edited, never a profile or {@code pluginManagement}
     * (BUG-20261023-03), and the compiler plugin it already declares is filled rather
     * than declared twice (BUG-20261023-01). The block it writes into appends to the
     * parent's, which holds the Vauban indexer (BUG-20261023-02).</p>
     */
    public static Result addAnnotationProcessorPath(String pom, ExtensionCoordinate codegen,
                                                    String version) {
        List<int[]> skipped = outsideProjectBuild(pom);
        int[] plugin = compilerPlugin(pom, skipped);
        if (plugin != null) {
            int aptOpen = indexWithin(pom, APT_OPEN, plugin, skipped);
            if (aptOpen >= 0) {
                return addToProcessorPaths(pom, aptOpen, codegen, version);
            }
            int configuration = indexWithin(pom, "<configuration>", plugin, skipped);
            if (configuration >= 0) {
                int close = indexOutside(pom, "</configuration>", configuration, skipped);
                return new Result(insertBeforeLine(pom, close,
                        processorPathsXml(codegen, version, childIndent(pom, close))), true);
            }
            int close = plugin[1] - "</plugin>".length();
            String indent = childIndent(pom, close);
            return new Result(insertBeforeLine(pom, close, indent + "<configuration>\n"
                    + processorPathsXml(codegen, version, indent + "    ")
                    + indent + "</configuration>\n"), true);
        }

        int pluginsClose = indexOutside(pom, "</plugins>", 0, skipped);
        if (pluginsClose >= 0) {
            return new Result(insertBeforeLine(pom, pluginsClose,
                    compilerPluginXml(codegen, version, childIndent(pom, pluginsClose))), true);
        }

        int buildClose = indexOutside(pom, "</build>", 0, skipped);
        if (buildClose >= 0) {
            String indent = childIndent(pom, buildClose);
            return new Result(insertBeforeLine(pom, buildClose, indent + "<plugins>\n"
                    + compilerPluginXml(codegen, version, indent + "    ")
                    + indent + "</plugins>\n"), true);
        }

        int proj = pom.indexOf("</project>");
        if (proj < 0) {
            throw new IllegalArgumentException("Not a valid pom.xml: missing </project>.");
        }
        String base = childIndent(pom, proj);
        String block = base + "<build>\n"
                + base + "    <plugins>\n"
                + compilerPluginXml(codegen, version, base + "        ")
                + base + "    </plugins>\n"
                + base + "</build>\n";
        return new Result(insertBeforeLine(pom, proj, block), true);
    }

    /**
     * Whether the project build's {@code annotationProcessorPaths} replaces the parent's
     * instead of appending to it, dropping the Vauban indexer the parent wires. False when
     * the project declares none.
     */
    public static boolean dropsInheritedProcessorPaths(String pom) {
        List<int[]> skipped = outsideProjectBuild(pom);
        int[] plugin = compilerPlugin(pom, skipped);
        int aptOpen = plugin == null ? -1 : indexWithin(pom, APT_OPEN, plugin, skipped);
        int tagEnd = aptOpen < 0 ? -1 : pom.indexOf('>', aptOpen);
        return tagEnd >= 0 && !appends(pom.substring(aptOpen, tagEnd + 1));
    }

    private static Result addToProcessorPaths(String pom, int aptOpen, ExtensionCoordinate codegen,
                                              String version) {
        int tagEnd = pom.indexOf('>', aptOpen);
        if (tagEnd < 0) {
            throw new IllegalArgumentException("Not a valid pom.xml: unclosed <annotationProcessorPaths>.");
        }
        String tag = pom.substring(aptOpen, tagEnd + 1);
        if (tag.endsWith("/>")) {
            String own = lineBefore(pom, aptOpen);
            String block = APPENDING_APT_OPEN + "\n" + pathXml(codegen, version, childIndent(pom, aptOpen))
                    + (isBlank(own) ? own : "") + APT_CLOSE;
            return new Result(pom.substring(0, aptOpen) + block + pom.substring(tagEnd + 1), true);
        }
        int aptClose = pom.indexOf(APT_CLOSE, tagEnd);
        if (aptClose < 0) {
            throw new IllegalArgumentException("Not a valid pom.xml: unclosed <annotationProcessorPaths>.");
        }
        String paths = pom.substring(tagEnd + 1, aptClose);
        boolean present = paths.contains("<artifactId>" + codegen.artifactId() + "</artifactId>");
        if (present && appends(tag)) {
            return new Result(pom, false);
        }
        String edited = present ? pom : insertBeforeLine(pom, aptClose, pathXml(codegen, version,
                isBlank(lineBefore(pom, aptClose)) ? childIndent(pom, aptClose) : "                    "));
        if (!appends(tag)) {
            edited = edited.substring(0, aptOpen) + APPENDING_APT_OPEN + edited.substring(tagEnd + 1);
        }
        return new Result(edited, true);
    }

    /** Whether an {@code <annotationProcessorPaths ...>} start tag keeps the inherited paths. */
    private static boolean appends(String tag) {
        return tag.contains("combine.children=\"append\"") && !tag.contains("combine.self=\"override\"");
    }

    /**
     * Removes the {@code <path>} of {@code codegen} from {@code annotationProcessorPaths},
     * the reverse of {@link #addAnnotationProcessorPath}. Unchanged when absent.
     */
    public static Result removeAnnotationProcessorPath(String pom, ExtensionCoordinate codegen) {
        List<int[]> skipped = outsideProjectBuild(pom);
        int[] plugin = compilerPlugin(pom, skipped);
        int aptOpen = plugin == null ? -1 : indexWithin(pom, APT_OPEN, plugin, skipped);
        int aptClose = aptOpen < 0 ? -1 : pom.indexOf(APT_CLOSE, aptOpen);
        String marker = "<artifactId>" + codegen.artifactId() + "</artifactId>";
        int from = aptOpen;
        while (aptClose >= 0) {
            int open = pom.indexOf("<path>", from);
            if (open < 0 || open > aptClose) {
                break;
            }
            int close = pom.indexOf("</path>", open);
            if (close < 0) {
                break;
            }
            int end = close + "</path>".length();
            if (pom.substring(open, end).contains(marker)) {
                return new Result(withoutEmptyCompilerPlugin(withoutLines(pom, open, end)), true);
            }
            from = end;
        }
        return new Result(pom, false);
    }

    /**
     * Undoes what {@link #addAnnotationProcessorPath} wrapped around a path once the last
     * one is gone: an empty appending {@code annotationProcessorPaths}, then a
     * {@code configuration} left empty, then a compiler plugin left with nothing but its
     * coordinates. Anything else the plugin configures keeps it.
     */
    private static String withoutEmptyCompilerPlugin(String pom) {
        List<int[]> skipped = outsideProjectBuild(pom);
        int[] plugin = compilerPlugin(pom, skipped);
        int aptOpen = plugin == null ? -1 : indexWithin(pom, APPENDING_APT_OPEN, plugin, skipped);
        if (aptOpen < 0) {
            return pom;
        }
        int aptEnd = pom.indexOf(APT_CLOSE, aptOpen) + APT_CLOSE.length();
        if (!isBlank(pom.substring(aptOpen + APPENDING_APT_OPEN.length(), aptEnd - APT_CLOSE.length()))) {
            return pom;
        }
        pom = withoutLines(pom, aptOpen, aptEnd);

        skipped = outsideProjectBuild(pom);
        plugin = compilerPlugin(pom, skipped);
        int configuration = indexWithin(pom, "<configuration>", plugin, skipped);
        if (configuration < 0) {
            return pom;
        }
        int configurationEnd = indexOutside(pom, "</configuration>", configuration, skipped)
                + "</configuration>".length();
        if (!isBlank(pom.substring(configuration + "<configuration>".length(),
                configurationEnd - "</configuration>".length()))) {
            return pom;
        }
        pom = withoutLines(pom, configuration, configurationEnd);

        plugin = compilerPlugin(pom, outsideProjectBuild(pom));
        String block = pom.substring(plugin[0], plugin[1]).replaceAll("\\s+", "");
        if (!block.equals("<plugin><groupId>org.apache.maven.plugins</groupId>" + COMPILER_ARTIFACT + "</plugin>")) {
            return pom;
        }
        return withoutLines(pom, plugin[0], plugin[1]);
    }

    private static String pathXml(ExtensionCoordinate codegen, String version, String indent) {
        return indent + "<path>\n"
                + indent + "    <groupId>" + codegen.groupId() + "</groupId>\n"
                + indent + "    <artifactId>" + codegen.artifactId() + "</artifactId>\n"
                + indent + "    <version>" + version + "</version>\n"
                + indent + "</path>\n";
    }

    private static String processorPathsXml(ExtensionCoordinate codegen, String version, String indent) {
        return indent + APPENDING_APT_OPEN + "\n"
                + pathXml(codegen, version, indent + "    ")
                + indent + APT_CLOSE + "\n";
    }

    private static String compilerPluginXml(ExtensionCoordinate codegen, String version, String indent) {
        return indent + "<plugin>\n"
                + indent + "    <groupId>org.apache.maven.plugins</groupId>\n"
                + indent + "    " + COMPILER_ARTIFACT + "\n"
                + indent + "    <configuration>\n"
                + processorPathsXml(codegen, version, indent + "        ")
                + indent + "    </configuration>\n"
                + indent + "</plugin>\n";
    }

    /**
     * The regions of a pom the project build does not read — comments, profiles,
     * {@code pluginManagement}, reporting — plus plugin executions, whose configuration
     * is not the plugin's own. Each is {@code [start, endExclusive]}.
     */
    private static List<int[]> outsideProjectBuild(String pom) {
        List<int[]> regions = new ArrayList<>();
        addRegions(pom, "<!--", "-->", regions);
        addRegions(pom, "<profiles>", "</profiles>", regions);
        addRegions(pom, "<pluginManagement>", "</pluginManagement>", regions);
        addRegions(pom, "<reporting>", "</reporting>", regions);
        addRegions(pom, "<executions>", "</executions>", regions);
        return regions;
    }

    private static void addRegions(String pom, String open, String close, List<int[]> regions) {
        for (int start = indexOutside(pom, open, 0, regions); start >= 0;
             start = indexOutside(pom, open, start + open.length(), regions)) {
            int end = pom.indexOf(close, start + open.length());
            if (end < 0) {
                return;
            }
            regions.add(new int[]{start, end + close.length()});
        }
    }

    /** First index of {@code needle} from {@code from} that lies in none of {@code skipped}, or -1. */
    private static int indexOutside(String pom, String needle, int from, List<int[]> skipped) {
        int i = pom.indexOf(needle, from);
        while (i >= 0) {
            int[] region = regionAt(i, skipped);
            if (region == null) {
                return i;
            }
            i = pom.indexOf(needle, region[1]);
        }
        return -1;
    }

    private static int[] regionAt(int index, List<int[]> regions) {
        for (int[] region : regions) {
            if (index >= region[0] && index < region[1]) {
                return region;
            }
        }
        return null;
    }

    /** Like {@link #indexOutside} but inside {@code element} only. */
    private static int indexWithin(String pom, String needle, int[] element, List<int[]> skipped) {
        int i = indexOutside(pom, needle, element[0], skipped);
        return i >= 0 && i < element[1] ? i : -1;
    }

    /** The project build's {@code maven-compiler-plugin} element as {@code [start, endExclusive]}, or null. */
    private static int[] compilerPlugin(String pom, List<int[]> skipped) {
        int artifact = indexOutside(pom, COMPILER_ARTIFACT, 0, skipped);
        if (artifact < 0) {
            return null;
        }
        int open = pom.lastIndexOf("<plugin>", artifact);
        int close = pom.indexOf("</plugin>", artifact);
        return open < 0 || close < 0 ? null : new int[]{open, close + "</plugin>".length()};
    }

    /** Inserts {@code block} at the start of the line holding {@code index}. */
    private static String insertBeforeLine(String pom, int index, String block) {
        int lineStart = pom.lastIndexOf('\n', index) + 1;
        return pom.substring(0, lineStart) + block + pom.substring(lineStart);
    }

    /** Removes {@code [start, end)} with its leading indentation and trailing newline. */
    private static String withoutLines(String pom, int start, int end) {
        int lineStart = pom.lastIndexOf('\n', start) + 1;
        if (end < pom.length() && pom.charAt(end) == '\n') {
            end++;
        }
        return pom.substring(0, lineStart) + pom.substring(end);
    }

    private static String lineBefore(String pom, int index) {
        return pom.substring(pom.lastIndexOf('\n', index) + 1, index);
    }

    /** The indentation of a child of the element whose tag sits at {@code index}. */
    private static String childIndent(String pom, int index) {
        String indent = lineBefore(pom, index);
        return isBlank(indent) ? indent + "    " : "    ";
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
