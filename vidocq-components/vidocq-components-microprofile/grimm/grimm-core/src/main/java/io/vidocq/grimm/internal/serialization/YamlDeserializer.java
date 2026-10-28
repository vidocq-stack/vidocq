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
package io.vidocq.grimm.internal.serialization;

import org.eclipse.microprofile.openapi.models.OpenAPI;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the YAML subset an OpenAPI document uses: block mappings and sequences at any
 * indentation (a sequence may sit at the indentation of its key), plain, quoted, multi-line and
 * block ({@code |}, {@code >} with {@code -} / {@code +} chomping) scalars, flow collections
 * ({@code [a, b]}, {@code {a: 1}}, possibly over several lines), comments and the
 * {@code ---} / {@code ...} document markers. Anchors, aliases, tags and complex keys are not
 * supported.
 */
public final class YamlDeserializer {

    private static final java.util.regex.Pattern INTEGER = java.util.regex.Pattern.compile("[-+]?[0-9]+");
    private static final java.util.regex.Pattern OCTAL = java.util.regex.Pattern.compile("0o[0-7]+");
    private static final java.util.regex.Pattern HEXADECIMAL = java.util.regex.Pattern.compile("0x[0-9a-fA-F]+");
    private static final java.util.regex.Pattern FLOAT = java.util.regex.Pattern.compile(
            "[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?");
    private static final java.util.regex.Pattern BLOCK_INDICATOR = java.util.regex.Pattern.compile(
            "[|>][-+]?[1-9]?");

    public OpenAPI deserialize(String yaml) {
        if (yaml == null) {
            throw new NullPointerException("yaml must not be null");
        }
        return OpenApiModelMapper.toOpenApi(parseRaw(yaml));
    }

    /** Parses a YAML document into maps, lists and scalars. */
    static Object parseRaw(String yaml) {
        return new NodeParser(yaml).parseDocument();
    }

    private static Object parseScalar(String raw) {
        return switch (raw) {
            case "null", "Null", "NULL", "~" -> null;
            case "true", "True", "TRUE" -> Boolean.TRUE;
            case "false", "False", "FALSE" -> Boolean.FALSE;
            default -> parseQuotedOrNumber(raw);
        };
    }

    private static Object parseQuotedOrNumber(String raw) {
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return unescape(raw.substring(1, raw.length() - 1));
        }
        if (raw.length() >= 2 && raw.startsWith("'") && raw.endsWith("'")) {
            return raw.substring(1, raw.length() - 1).replace("''", "'");
        }
        return parseNumber(raw);
    }

    /**
     * Reads a plain scalar as a number following the YAML 1.2 core schema: {@code [-+]?[0-9]+}
     * (integer), {@code 0o} octal, {@code 0x} hexadecimal and
     * {@code [-+]?(\.[0-9]+|[0-9]+(\.[0-9]*)?)([eE][-+]?[0-9]+)?} (float). Anything else is
     * returned unchanged as a string.
     */
    private static Object parseNumber(String raw) {
        try {
            if (INTEGER.matcher(raw).matches()) {
                return parseInteger(raw.startsWith("+") ? raw.substring(1) : raw);
            }
            if (OCTAL.matcher(raw).matches()) {
                return Long.parseLong(raw.substring(2), 8);
            }
            if (HEXADECIMAL.matcher(raw).matches()) {
                return Long.parseLong(raw.substring(2), 16);
            }
            if (FLOAT.matcher(raw).matches()) {
                return Double.parseDouble(raw);
            }
        } catch (NumberFormatException _) {
            // an octal or hexadecimal literal beyond a long stays a string
        }
        return raw;
    }

    private static Number parseInteger(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException _) {
            return new java.math.BigInteger(digits);
        }
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 == value.length()) {
                out.append(c);
                i++;
            } else if (value.charAt(i + 1) == 'u' && i + 6 <= value.length()) {
                out.append((char) Integer.parseInt(value.substring(i + 2, i + 6), 16));
                i += 6;
            } else {
                out.append(escaped(value.charAt(i + 1)));
                i += 2;
            }
        }
        return out.toString();
    }

    private static char escaped(char next) {
        return switch (next) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case '0' -> '\0';
            default -> next;
        };
    }

    /** Whether a quote at {@code i} opens a quoted scalar: it must start a token. */
    private static boolean opensQuote(CharSequence text, int i) {
        char c = i < text.length() ? text.charAt(i) : ' ';
        if (c != '"' && c != '\'') {
            return false;
        }
        int previous = i - 1;
        while (previous >= 0 && text.charAt(previous) == ' ') {
            previous--;
        }
        return previous < 0 || "[{,:-?".indexOf(text.charAt(previous)) >= 0;
    }

    /** Index where a quoted scalar opened at {@code start} closes, or -1. */
    private static int closingQuote(CharSequence text, int start) {
        char quote = text.charAt(start);
        int i = start + 1;
        while (i < text.length()) {
            if (isEscape(text, i, quote)) {
                i += 2;
            } else if (text.charAt(i) == quote) {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    /** A backslash in a double-quoted scalar, a doubled quote in a single-quoted one. */
    private static boolean isEscape(CharSequence text, int i, char quote) {
        char c = text.charAt(i);
        if (quote == '"') {
            return c == '\\';
        }
        return c == quote && i + 1 < text.length() && text.charAt(i + 1) == quote;
    }

    private static String stripComment(String line) {
        int i = 0;
        while (i < line.length()) {
            if (opensQuote(line, i)) {
                int close = closingQuote(line, i);
                if (close < 0) {
                    return line;
                }
                i = close + 1;
            } else if (line.charAt(i) == '#' && (i == 0 || line.charAt(i - 1) == ' ')) {
                return line.substring(0, i);
            } else {
                i++;
            }
        }
        return line;
    }

    private static int indentOf(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        return indent;
    }

    private static boolean isListItem(String text) {
        return "-".equals(text) || text.startsWith("- ");
    }

    private static boolean isFlowStart(CharSequence text) {
        return !text.isEmpty() && (text.charAt(0) == '[' || text.charAt(0) == '{');
    }

    /** Index of the colon that separates a block mapping key from its value, or -1. */
    private static int mappingColon(String text) {
        if (isFlowStart(text)) {
            return -1;
        }
        int from = 0;
        if (opensQuote(text, 0)) {
            int close = closingQuote(text, 0);
            if (close < 0) {
                return -1;
            }
            from = close + 1;
            if (from < text.length() && text.charAt(from) == ':') {
                return from;
            }
        }
        for (int i = text.indexOf(':', from); i >= 0; i = text.indexOf(':', i + 1)) {
            if (i + 1 == text.length() || text.charAt(i + 1) == ' ') {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeKey(String key) {
        String trimmed = key.trim();
        if (trimmed.length() >= 2 && (trimmed.startsWith("\"") || trimmed.startsWith("'"))
                && trimmed.endsWith(trimmed.substring(0, 1))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static IllegalArgumentException invalid(String what, int lineIndex, String line) {
        return new IllegalArgumentException("Invalid YAML " + what + " at line " + (lineIndex + 1) + ": " + line);
    }

    private static final class NodeParser {
        private final List<String> lines;
        private int index;

        private NodeParser(String yaml) {
            this.lines = new ArrayList<>(yaml.lines().toList());
        }

        private Object parseDocument() {
            skipBlankLines();
            if (index < lines.size() && "---".equals(content(index))) {
                index++;
            }
            int indent = nextIndent();
            if (indent < 0) {
                return Map.of();
            }
            Object root = parseNode(indent);
            if (nextIndent() >= 0) {
                throw invalid("indentation", index, lines.get(index));
            }
            return root;
        }

        private Object parseNode(int indent) {
            return isListItem(content(index)) ? parseList(indent) : parseMap(indent);
        }

        private Map<String, Object> parseMap(int indent) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            while (nextIndent() == indent && !isListItem(content(index))) {
                String text = content(index);
                int colon = mappingColon(text);
                if (colon < 0) {
                    throw invalid("line", index, lines.get(index));
                }
                index++;
                result.put(normalizeKey(text.substring(0, colon)),
                        parseValue(text.substring(colon + 1).trim(), indent, true));
            }
            return result;
        }

        private List<Object> parseList(int indent) {
            ArrayList<Object> result = new ArrayList<>();
            while (nextIndent() == indent && isListItem(content(index))) {
                String line = lines.get(index);
                String rest = content(index).substring(1).trim();
                if (isListItem(rest) || mappingColon(rest) > 0) {
                    // "- key: value" or "- - item": the item is a node at the column after the dash
                    int itemIndent = indent + 1 + indentOf(line.substring(indent + 1));
                    lines.set(index, " ".repeat(itemIndent) + line.substring(itemIndent));
                    result.add(parseNode(itemIndent));
                } else {
                    index++;
                    result.add(parseValue(rest, indent, false));
                }
            }
            return result;
        }

        private Object parseValue(String rest, int parentIndent, boolean sequenceAtSameIndent) {
            if (rest.isEmpty()) {
                return parseNested(parentIndent, sequenceAtSameIndent);
            }
            if (BLOCK_INDICATOR.matcher(rest).matches()) {
                return parseBlockScalar(parentIndent, rest);
            }
            String scalar = joinContinuationLines(rest, parentIndent);
            return isFlowStart(scalar) ? new FlowParser(scalar).parseAll() : parseScalar(scalar);
        }

        private Object parseNested(int parentIndent, boolean sequenceAtSameIndent) {
            int child = nextIndent();
            if (child > parentIndent
                    || (sequenceAtSameIndent && child == parentIndent && isListItem(content(index)))) {
                return parseNode(child);
            }
            return null;
        }

        /** A multi-line plain or quoted scalar, or a flow collection written over several lines. */
        private String joinContinuationLines(String first, int parentIndent) {
            StringBuilder text = new StringBuilder(first);
            boolean flow = isFlowStart(first);
            while (true) {
                int next = nextIndent();
                boolean open = flow && !FlowParser.isBalanced(text);
                if (next < 0 || (next <= parentIndent && !open)) {
                    return text.toString();
                }
                text.append(' ').append(content(index));
                index++;
            }
        }

        private String parseBlockScalar(int parentIndent, String indicator) {
            List<String> block = new ArrayList<>();
            int blockIndent = -1;
            while (index < lines.size()) {
                String line = lines.get(index);
                if (!line.isBlank()) {
                    int indent = indentOf(line);
                    if (indent <= parentIndent || (blockIndent >= 0 && indent < blockIndent)) {
                        break;
                    }
                    blockIndent = blockIndent < 0 ? indent : blockIndent;
                }
                block.add(line.isBlank() ? "" : line.substring(blockIndent));
                index++;
            }
            int trailingBlankLines = 0;
            while (!block.isEmpty() && block.getLast().isEmpty()) {
                block.removeLast();
                trailingBlankLines++;
            }
            String body = indicator.charAt(0) == '>' ? fold(block) : String.join("\n", block);
            return chomp(body, indicator, trailingBlankLines);
        }

        private static String fold(List<String> block) {
            StringBuilder folded = new StringBuilder();
            for (String line : block) {
                if (line.isEmpty()) {
                    folded.append('\n');
                } else {
                    if (!folded.isEmpty() && folded.charAt(folded.length() - 1) != '\n') {
                        folded.append(' ');
                    }
                    folded.append(line);
                }
            }
            return folded.toString();
        }

        private static String chomp(String body, String indicator, int trailingBlankLines) {
            if (indicator.contains("-") || body.isEmpty()) {
                return body;
            }
            if (indicator.contains("+")) {
                return body + "\n".repeat(trailingBlankLines + 1);
            }
            return body + "\n";
        }

        /** Indentation of the next significant line, or -1 at the end of the document. */
        private int nextIndent() {
            skipBlankLines();
            if (index >= lines.size()) {
                return -1;
            }
            String line = lines.get(index);
            String marker = content(index);
            if (indentOf(line) == 0 && ("---".equals(marker) || "...".equals(marker))) {
                return -1;
            }
            return indentOf(line);
        }

        private String content(int lineIndex) {
            return stripComment(lines.get(lineIndex)).trim();
        }

        private void skipBlankLines() {
            while (index < lines.size() && content(index).isEmpty()) {
                index++;
            }
        }
    }

    /** A flow collection ({@code [a, b]}, {@code {k: v}}) read from a single (joined) line. */
    private static final class FlowParser {
        private final String text;
        private int pos;

        private FlowParser(String text) {
            this.text = text;
        }

        static boolean isBalanced(CharSequence text) {
            int depth = 0;
            int i = 0;
            while (i < text.length()) {
                if (opensQuote(text, i)) {
                    int close = closingQuote(text, i);
                    if (close < 0) {
                        return false;
                    }
                    i = close;
                }
                depth += depthChange(text.charAt(i));
                i++;
            }
            return depth <= 0;
        }

        private static int depthChange(char c) {
            return switch (c) {
                case '[', '{' -> 1;
                case ']', '}' -> -1;
                default -> 0;
            };
        }

        private Object parseAll() {
            Object value = parseValue();
            skipSpaces();
            if (pos != text.length()) {
                throw invalid();
            }
            return value;
        }

        private Object parseValue() {
            skipSpaces();
            if (pos >= text.length()) {
                throw invalid();
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '[' -> parseSequence();
                case '{' -> parseMapping();
                case '"', '\'' -> parseQuoted();
                default -> parseScalar(plain(",]}"));
            };
        }

        private List<Object> parseSequence() {
            pos++;
            ArrayList<Object> items = new ArrayList<>();
            while (true) {
                skipSpaces();
                if (consume(']')) {
                    return items;
                }
                items.add(parseValue());
                skipSpaces();
                if (!consume(',')) {
                    expect(']');
                    return items;
                }
            }
        }

        private Map<String, Object> parseMapping() {
            pos++;
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            while (true) {
                skipSpaces();
                if (consume('}')) {
                    return map;
                }
                String key = peekQuote() ? parseQuoted() : plain(":,}");
                skipSpaces();
                map.put(key, consume(':') ? parseValueOrNull() : null);
                skipSpaces();
                if (!consume(',')) {
                    expect('}');
                    return map;
                }
            }
        }

        private Object parseValueOrNull() {
            skipSpaces();
            if (pos < text.length() && ",}".indexOf(text.charAt(pos)) >= 0) {
                return null;
            }
            return parseValue();
        }

        private boolean peekQuote() {
            return pos < text.length() && (text.charAt(pos) == '"' || text.charAt(pos) == '\'');
        }

        private String parseQuoted() {
            int close = closingQuote(text, pos);
            if (close < 0) {
                throw invalid();
            }
            String raw = text.substring(pos + 1, close);
            boolean doubleQuoted = text.charAt(pos) == '"';
            pos = close + 1;
            return doubleQuoted ? unescape(raw) : raw.replace("''", "'");
        }

        private String plain(String stops) {
            int start = pos;
            while (pos < text.length() && stops.indexOf(text.charAt(pos)) < 0) {
                pos++;
            }
            String value = text.substring(start, pos).trim();
            if (value.isEmpty()) {
                throw invalid();
            }
            return value;
        }

        private boolean consume(char expected) {
            if (pos < text.length() && text.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw invalid();
            }
        }

        private void skipSpaces() {
            while (pos < text.length() && text.charAt(pos) == ' ') {
                pos++;
            }
        }

        private IllegalArgumentException invalid() {
            return new IllegalArgumentException("Invalid YAML flow collection at column " + (pos + 1) + ": " + text);
        }
    }
}
