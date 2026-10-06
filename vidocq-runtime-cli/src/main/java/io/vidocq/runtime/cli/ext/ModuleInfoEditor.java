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

    /** Outcome of an edit: the (possibly unchanged) source and whether it changed. */
    public record Result(String source, boolean changed) {}

    private ModuleInfoEditor() {}

    /**
     * Inserts the missing {@code directives} (written without their {@code ;}) before the
     * module's closing brace, marked for {@code id}.
     *
     * @throws IllegalArgumentException when the source has no closing brace
     */
    public static Result add(String source, String id, List<String> directives) {
        List<String> declared = declared(source);
        StringBuilder block = new StringBuilder();
        for (String directive : directives) {
            String normalized = normalize(directive);
            if (!declared.contains(normalized)) {
                block.append("    ").append(directive).append("; ").append(marker(id)).append('\n');
                declared.add(normalized);
            }
        }
        if (block.isEmpty()) {
            return new Result(source, false);
        }
        int close = source.lastIndexOf('}');
        if (close < 0) {
            throw new IllegalArgumentException("Not a valid module-info.java: missing closing brace.");
        }
        int lineStart = source.lastIndexOf('\n', close) + 1;
        return new Result(source.substring(0, lineStart) + block + source.substring(lineStart), true);
    }

    /**
     * Removes the lines marked for {@code id}, and a {@code requires} of its
     * {@code module} however it was written: the module is gone with the dependency.
     */
    public static Result remove(String source, String id, String module) {
        String marker = marker(id);
        String requiresModule = module == null ? null : normalize("requires " + module);
        StringBuilder out = new StringBuilder();
        boolean changed = false;
        for (String line : source.split("(?<=\n)")) {
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

    private static String marker(String id) {
        return "// vidocq:" + id;
    }

    /** The directives a source declares, normalized. */
    private static List<String> declared(String source) {
        List<String> declared = new ArrayList<>();
        for (String line : source.split("\n")) {
            String code = line.contains("//") ? line.substring(0, line.indexOf("//")) : line;
            for (String statement : code.split(";")) {
                String normalized = normalize(statement);
                if (normalized.startsWith("requires ") || normalized.startsWith("opens ")) {
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
