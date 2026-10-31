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
package io.vidocq.runtime.tooling;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Adds and removes an extension's directives in {@code module-info.java} — the
 * {@code module-info} counterpart of {@link PomEditor}. Every directive it adds ends with
 * {@code // vidocq:<id>}, so removing the extension takes back exactly what adding it gave,
 * and never a line the developer wrote.
 *
 * <p>Pure: plain text edits, no parser. A directive the source already declares, whatever
 * its spacing or marker, is never duplicated.</p>
 */
public final class ModuleInfoEditor {

    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final String OPENS = "opens ";
    private static final String MARKER_PREFIX = "// vidocq:";
    /** Splits a source into its lines, each with its line feed. */
    private static final String LINE_ENDS = "(?<=\n)";

    /**
     * The id that marks a package opened for reflection — by {@code vidocq doctor --fix}, {@code
     * vidocq:complete-module-info} or {@code vidocq dev} — as {@code // vidocq:<id>} marks an extension's lines. No
     * extension has it, so {@code extension remove} never takes such a line away.
     */
    public static final String REFLECTION = "reflection";

    /** The comment over the block of packages opened for reflection, which comes last in the module. */
    public static final String REFLECTION_HEADER = "// added by vidocq: the runtime reaches these packages by reflection";

    /** The header {@code vidocq:complete-module-info} wrote before BUG-20261031-14; its block is merged too. */
    private static final String LEGACY_COMPLETE_HEADER = "// added by vidocq:complete-module-info";

    /** Outcome of an edit: the (possibly unchanged) source and whether it changed. */
    public record Result(String source, boolean changed) {}

    /**
     * Outcome of {@link #toOpenModule}: the source, the {@code opens} lines it dropped as they were written, and the
     * packages of those no {@code // vidocq:} marker claimed, which the developer wrote.
     */
    public record OpenModule(String source, List<String> removed, List<String> handWritten) {}

    private ModuleInfoEditor() {}

    /**
     * Whether the source declares an {@code open module}, which opens every package: an {@code opens} line inside it
     * does not compile. Comments are skipped.
     */
    public static boolean isOpenModule(String source) {
        int module = moduleKeyword(source);
        if (module < 0) {
            return false;
        }
        int end = module;
        while (end > 0 && Character.isWhitespace(source.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1))) {
            start--;
        }
        return source.substring(start, end).equals("open");
    }

    /**
     * The source as an {@code open module}: the keyword added, and every {@code opens} line dropped — javac refuses
     * them in an open module — with the header of the block of packages opened for reflection. Each dropped line is
     * returned as written; those no {@code // vidocq:} marker claimed are named by package, the developer wrote them.
     */
    public static OpenModule toOpenModule(String source) {
        String body = split(source).rest();
        List<String> removed = new ArrayList<>();
        List<String> handWritten = new ArrayList<>();
        for (String line : source.split(LINE_ENDS)) {
            String stripped = line.strip();
            if (code(stripped).startsWith(OPENS)) {
                removed.add(stripped);
            }
        }
        StringBuilder kept = new StringBuilder();
        for (String line : body.split(LINE_ENDS)) {
            String stripped = line.strip();
            String code = code(stripped);
            if (code.startsWith(OPENS)) {
                if (!stripped.contains(MARKER_PREFIX)) {
                    handWritten.add(SPACES.split(code)[1]);
                }
            } else {
                kept.append(line);
            }
        }
        String opened = kept.toString();
        if (!isOpenModule(opened)) {
            int module = moduleKeyword(opened);
            if (module >= 0) {
                opened = opened.substring(0, module) + "open " + opened.substring(module);
            }
        }
        return new OpenModule(opened, removed, handWritten);
    }

    /** Where the {@code module} keyword of the declaration starts, outside comments; -1 when there is none. */
    private static int moduleKeyword(String source) {
        int i = 0;
        int length = source.length();
        while (i < length) {
            if (source.startsWith("//", i)) {
                int eol = source.indexOf('\n', i);
                i = eol < 0 ? length : eol + 1;
            } else if (source.startsWith("/*", i)) {
                int close = source.indexOf("*/", i + 2);
                i = close < 0 ? length : close + 2;
            } else if (source.startsWith("module", i) && keywordAt(source, i, "module".length())) {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    /** Whether the word of {@code length} characters at {@code at} stands alone, followed by a blank. */
    private static boolean keywordAt(String source, int at, int length) {
        boolean before = at == 0 || !Character.isJavaIdentifierPart(source.charAt(at - 1));
        int after = at + length;
        return before && after < source.length() && Character.isWhitespace(source.charAt(after))
                && (at == 0 || source.charAt(at - 1) != '.');
    }

    /**
     * Inserts the missing {@code directives} (written without their {@code ;}) before the
     * module's closing brace, marked for {@code id}.
     *
     * @throws IllegalArgumentException when the source has no closing brace
     */
    public static Result add(String source, String id, List<String> directives) {
        List<String> declared = declared(source);
        StringBuilder block = new StringBuilder();
        for (String directive : withoutOpensIfOpen(source, directives)) {
            String normalized = normalize(directive);
            if (!declared.contains(normalized)) {
                block.append("    ").append(directive).append("; ").append(marker(id)).append('\n');
                declared.add(normalized);
            }
        }
        if (block.isEmpty()) {
            return new Result(source, false);
        }
        if (source.lastIndexOf('}') < 0) {
            throw new IllegalArgumentException("Not a valid module-info.java: missing closing brace.");
        }
        Split split = split(source);
        String rest = split.rest();
        int at = afterLastExtensionLine(rest);
        return new Result(emit(rest.substring(0, at) + block + rest.substring(at), split.reflection()), true);
    }

    /**
     * Opens each of {@code packages} the source does not open yet, in the one block of packages opened for
     * reflection, last in the module, each line marked {@code // vidocq:reflection}. Older blocks — several headers,
     * extension lines between them, the header {@code vidocq:complete-module-info} wrote — are merged into it. The
     * source is returned as is when every package is opened already, or without a closing brace.
     */
    public static String openForReflection(String source, Collection<String> packages) {
        if (isOpenModule(source)) {
            return source;
        }
        List<String> toOpen = new ArrayList<>();
        List<String> declared = declared(source);
        for (String pkg : new LinkedHashSet<>(packages)) {
            if (!declared.contains(OPENS + pkg)) {
                toOpen.add(OPENS + pkg);
            }
        }
        if (toOpen.isEmpty() || source.lastIndexOf('}') < 0) {
            return source;
        }
        Split split = split(source);
        List<String> reflection = new ArrayList<>(split.reflection());
        reflection.addAll(toOpen);
        return emit(split.rest(), reflection);
    }

    /**
     * Removes the lines marked for {@code id}, and a {@code requires} of its
     * {@code module} however it was written: the module is gone with the dependency.
     */
    public static Result remove(String source, String id, String module) {
        Split split = split(source);
        Result removed = removeFrom(split.rest(), id, module);
        return removed.changed() ? new Result(emit(removed.source(), split.reflection()), true) : new Result(source, false);
    }

    private static Result removeFrom(String source, String id, String module) {
        String marker = marker(id);
        String requiresModule = module == null ? null : normalize("requires " + module);
        StringBuilder out = new StringBuilder();
        boolean changed = false;
        for (String line : source.split(LINE_ENDS)) {
            String code = line.contains("//") ? line.substring(0, line.indexOf("//")) : line;
            boolean marked = line.stripTrailing().endsWith(marker);
            boolean ofModule = requiresModule != null
                    && normalize(code.replace(";", "")).equals(requiresModule);
            if (marked || ofModule) {
                changed = true;
            } else {
                out.append(line);
            }
        }
        return new Result(out.toString(), changed);
    }

    /** The {@code directives} (written without their {@code ;}) that {@code source} does not declare, in order. */
    public static List<String> missing(String source, List<String> directives) {
        List<String> declared = declared(source);
        return withoutOpensIfOpen(source, directives).stream().filter(d -> !declared.contains(normalize(d))).toList();
    }

    /** {@code directives} without their {@code opens} when the source is an open module, which opens every package. */
    private static List<String> withoutOpensIfOpen(String source, List<String> directives) {
        if (!isOpenModule(source)) {
            return directives;
        }
        return directives.stream().filter(d -> !normalize(d).startsWith(OPENS)).toList();
    }

    /** A source without its block of packages opened for reflection, and that block's directives, in order. */
    private record Split(String rest, List<String> reflection) {}

    /**
     * Takes the packages opened for reflection out of {@code source}: the lines marked {@code // vidocq:reflection},
     * and the unmarked {@code opens} right under a header, as earlier versions wrote them; the headers go too, with
     * the blank line before each.
     */
    private static Split split(String source) {
        List<String> reflection = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        boolean underHeader = false;
        for (String line : source.split(LINE_ENDS)) {
            String stripped = line.strip();
            if (stripped.equals(REFLECTION_HEADER) || stripped.equals(LEGACY_COMPLETE_HEADER)) {
                if (!kept.isEmpty() && kept.getLast().isBlank()) {
                    kept.removeLast();
                }
                underHeader = true;
            } else if (opensForReflection(stripped, underHeader)) {
                String code = code(stripped);
                if (!reflection.contains(code)) {
                    reflection.add(code);
                }
            } else {
                underHeader = false;
                kept.add(line);
            }
        }
        return new Split(String.join("", kept), reflection);
    }

    /**
     * Whether a line opens a package for reflection: marked {@code // vidocq:reflection}, or, right under a header, an
     * {@code opens} that no extension marks, as earlier versions wrote them.
     */
    private static boolean opensForReflection(String stripped, boolean underHeader) {
        if (stripped.endsWith(marker(REFLECTION))) {
            return true;
        }
        return underHeader && code(stripped).startsWith(OPENS) && !stripped.contains(MARKER_PREFIX);
    }

    /** The directive of a line, without its comment nor its {@code ;}, normalized. */
    private static String code(String line) {
        int comment = line.indexOf("//");
        return normalize((comment < 0 ? line : line.substring(0, comment)).replace(";", ""));
    }

    /** {@code rest} with the block of {@code reflection} directives before its closing brace, after a blank line. */
    private static String emit(String rest, List<String> reflection) {
        if (reflection.isEmpty()) {
            return rest;
        }
        int close = rest.lastIndexOf('}');
        int lineStart = rest.lastIndexOf('\n', close) + 1;
        // One blank line before the block, whatever the source left before its closing brace.
        String before = rest.substring(0, lineStart).stripTrailing() + "\n";
        StringBuilder block = new StringBuilder("\n    ").append(REFLECTION_HEADER).append('\n');
        for (String directive : reflection) {
            block.append("    ").append(directive).append("; ").append(marker(REFLECTION)).append('\n');
        }
        return before + block + rest.substring(lineStart);
    }

    /**
     * Where an extension's lines go in {@code source}: after the last line marked for an extension, so that they stay
     * together, else on the line of the closing brace.
     */
    private static int afterLastExtensionLine(String source) {
        int at = -1;
        int offset = 0;
        for (String line : source.split(LINE_ENDS)) {
            offset += line.length();
            if (line.contains(MARKER_PREFIX)) {
                at = offset;
            }
        }
        if (at >= 0) {
            return at;
        }
        int close = source.lastIndexOf('}');
        return source.lastIndexOf('\n', close) + 1;
    }

    private static String marker(String id) {
        return MARKER_PREFIX + id;
    }

    /** The directives a source declares, normalized. */
    private static List<String> declared(String source) {
        List<String> declared = new ArrayList<>();
        for (String line : source.split("\n")) {
            String code = line.contains("//") ? line.substring(0, line.indexOf("//")) : line;
            for (String statement : code.split(";")) {
                String normalized = normalize(statement);
                if (normalized.startsWith("requires ") || normalized.startsWith(OPENS)
                        || normalized.startsWith("exports ")) {
                    declared.add(normalized);
                }
            }
        }
        return declared;
    }

    private static String normalize(String directive) {
        return SPACES.matcher(directive.strip()).replaceAll(" ");
    }
}
