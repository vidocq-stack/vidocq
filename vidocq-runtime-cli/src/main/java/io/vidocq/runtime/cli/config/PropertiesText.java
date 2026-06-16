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
package io.vidocq.runtime.cli.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Pure, line-preserving editor for {@code .properties} text.
 *
 * <p>Reads and rewrites property text without disturbing comments, blank lines
 * or key ordering — {@link java.util.Properties} cannot do this because it drops
 * comments and reorders keys on store. Only the keys we touch are changed; a new
 * key is appended at the end. Keys may be separated from values by {@code =} or
 * {@code :}. Escaped/continued lines are not supported (Vidocq keys are simple
 * dotted identifiers), which keeps the editor predictable.
 */
public final class PropertiesText {

    private PropertiesText() {}

    /** The value bound to {@code key}, if present. */
    public static Optional<String> get(String text, String key) {
        for (String line : text.split("\n", -1)) {
            Entry entry = parse(line);
            if (entry != null && entry.key().equals(key)) {
                return Optional.of(entry.value());
            }
        }
        return Optional.empty();
    }

    /** Every key/value pair, in file order. */
    public static Map<String, String> entries(String text) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : text.split("\n", -1)) {
            Entry entry = parse(line);
            if (entry != null) {
                map.put(entry.key(), entry.value());
            }
        }
        return map;
    }

    /**
     * Return {@code text} with {@code key} bound to {@code value}: the first
     * existing assignment is rewritten in place (preserving the original
     * separator), otherwise a {@code key=value} line is appended.
     */
    public static String set(String text, String key, String value) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Entry entry = parse(lines[i]);
            if (entry != null && entry.key().equals(key)) {
                lines[i] = key + entry.separator() + value;
                return String.join("\n", lines);
            }
        }
        return append(text, key + "=" + value);
    }

    private static String append(String text, String line) {
        if (text.isEmpty()) {
            return line + "\n";
        }
        if (text.endsWith("\n")) {
            return text + line + "\n";
        }
        return text + "\n" + line + "\n";
    }

    private record Entry(String key, String separator, String value) {}

    private static Entry parse(String rawLine) {
        String line = rawLine;
        // Strip a trailing carriage return from CRLF input.
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        String trimmed = line.stripLeading();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return null;
        }
        int eq = indexOfSeparator(line);
        if (eq < 0) {
            return null;
        }
        String key = line.substring(0, eq).trim();
        if (key.isEmpty()) {
            return null;
        }
        char sep = line.charAt(eq);
        // Preserve the spacing around the separator (e.g. " = ").
        int valueStart = eq + 1;
        String left = line.substring(0, eq);
        String leftSpace = left.substring(key.length());
        String rest = line.substring(valueStart);
        String rightSpace = rest.substring(0, rest.length() - rest.stripLeading().length());
        String value = rest.stripLeading();
        String separator = leftSpace + sep + rightSpace;
        return new Entry(key, separator, value);
    }

    private static int indexOfSeparator(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '=' || c == ':') {
                return i;
            }
        }
        return -1;
    }
}
