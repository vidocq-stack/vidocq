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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The little JSON the pools panel needs, without a library (SQL spec §4), as Mansart Data's panel has its own:
 * {@link #parse} reads a statement argument into a tree of {@link Map} (members in order), {@link List},
 * {@link String}, {@link BigDecimal} (its text kept), {@link Boolean} and {@code null}; {@link #write} writes such a
 * tree, compact: a language, a replay, the details of a call. The console already checked that an argument is one
 * JSON object nesting at most 64 levels; the reader checks again, so that it never trusts its caller.
 */
final class Json {

    /** How deep a value may nest: the console's own limit. */
    static final int MAX_DEPTH = 64;

    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /**
     * The tree of {@code text}.
     *
     * @throws IllegalArgumentException when it is not one JSON value, or nests deeper than {@value #MAX_DEPTH}
     */
    static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("no JSON");
        }
        Json reader = new Json(text);
        reader.blanks();
        Object value = reader.value(0);
        reader.blanks();
        if (reader.at != text.length()) {
            throw reader.error();
        }
        return value;
    }

    /**
     * {@code value} as compact JSON: the types of the class comment, any other number as its text, anything else
     * quoted.
     */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private Object value(int depth) {
        if (depth >= MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nests deeper than " + MAX_DEPTH + " levels");
        }
        if (at >= text.length()) {
            throw error();
        }
        return switch (text.charAt(at)) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> members = new LinkedHashMap<>();
        at++;
        blanks();
        if (next('}')) {
            return members;
        }
        do {
            blanks();
            if (at >= text.length() || text.charAt(at) != '"') {
                throw error();
            }
            String name = string();
            blanks();
            expect(':');
            blanks();
            members.put(name, value(depth + 1));
            blanks();
        } while (next(','));
        expect('}');
        return members;
    }

    private List<Object> array(int depth) {
        List<Object> elements = new ArrayList<>();
        at++;
        blanks();
        if (next(']')) {
            return elements;
        }
        do {
            blanks();
            elements.add(value(depth + 1));
            blanks();
        } while (next(','));
        expect(']');
        return elements;
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw error();
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw error();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (at >= text.length()) {
                throw error();
            }
            char escaped = text.charAt(at++);
            switch (escaped) {
                case '"', '\\', '/' -> out.append(escaped);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> out.append(unicode());
                default -> throw error();
            }
        }
    }

    private char unicode() {
        if (at + 4 > text.length()) {
            throw error();
        }
        int code = 0;
        for (int i = 0; i < 4; i++) {
            char digit = text.charAt(at + i);
            if (!HexFormat.isHexDigit(digit)) {
                throw error();
            }
            code = code * 16 + HexFormat.fromHexDigit(digit);
        }
        at += 4;
        return (char) code;
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) {
            throw error();
        }
        at += word.length();
        return value;
    }

    private BigDecimal number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        String number = text.substring(start, at);
        if (!NUMBER.matcher(number).matches()) {
            at = start;
            throw error();
        }
        try {
            return new BigDecimal(number);
        } catch (NumberFormatException exponentTooLarge) {
            at = start;
            throw error();
        }
    }

    private void blanks() {
        while (at < text.length() && " \t\n\r".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
    }

    private boolean next(char c) {
        if (at < text.length() && text.charAt(at) == c) {
            at++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!next(c)) {
            throw error();
        }
    }

    private IllegalArgumentException error() {
        return new IllegalArgumentException("not valid JSON at character " + (at + 1));
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String text -> quote(text, out);
            case Boolean flag -> out.append(flag);
            case Double d when d.isNaN() || d.isInfinite() -> quote(d.toString(), out);
            case Float f when f.isNaN() || f.isInfinite() -> quote(f.toString(), out);
            case Number number -> out.append(number);
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> member : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    quote(String.valueOf(member.getKey()), out);
                    out.append(':');
                    write(member.getValue(), out);
                }
                out.append('}');
            }
            case Collection<?> elements -> {
                out.append('[');
                boolean first = true;
                for (Object element : elements) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(element, out);
                }
                out.append(']');
            }
            default -> quote(value.toString(), out);
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
