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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The JSON the panel reads and writes: a statement the page sends, its parameters, and the language and schemas it
 * declares. A value is a {@link Map} of members in their order, a {@link List}, a {@link String}, a {@link Long} or a
 * {@link BigDecimal}, a {@link Boolean} or {@code null}.
 */
final class Json {

    /** How deep arrays and objects nest at most. */
    static final int MAX_DEPTH = 64;

    private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?");
    private static final String END = "Unexpected end of the text";

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /**
     * The value {@code text} holds.
     *
     * @throws IllegalArgumentException naming what is wrong and where, when {@code text} is no JSON value
     */
    static Object parse(String text) {
        Json reader = new Json(Objects.requireNonNull(text, "text"));
        Object value = reader.value(0);
        reader.blanks();
        if (reader.at < text.length()) {
            throw reader.wrong("Unexpected character '" + text.charAt(reader.at) + "'");
        }
        return value;
    }

    /** {@code value} as JSON; a value of another type is written as its {@link String#valueOf}. */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    /** A map of {@code keysAndValues}, in their order, for a JSON object whose members keep it. */
    static Map<String, Object> object(Object... keysAndValues) {
        Map<String, Object> members = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            members.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return members;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw wrong("Nested deeper than " + MAX_DEPTH + " levels");
        }
        blanks();
        if (at >= text.length()) {
            throw wrong(END);
        }
        return switch (text.charAt(at)) {
            case '{' -> members(depth);
            case '[' -> elements(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> members(int depth) {
        Map<String, Object> members = new LinkedHashMap<>();
        at++;
        blanks();
        if (take('}')) {
            return members;
        }
        do {
            blanks();
            String name = string();
            blanks();
            expect(':');
            members.put(name, value(depth + 1));
            blanks();
        } while (take(','));
        expect('}');
        return members;
    }

    private List<Object> elements(int depth) {
        List<Object> elements = new ArrayList<>();
        at++;
        blanks();
        if (take(']')) {
            return elements;
        }
        do {
            elements.add(value(depth + 1));
            blanks();
        } while (take(','));
        expect(']');
        return elements;
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (at < text.length()) {
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                out.append(escape());
            } else if (c < ' ') {
                throw wrong("Unescaped control character");
            } else {
                out.append(c);
            }
        }
        throw wrong(END);
    }

    private char escape() {
        if (at >= text.length()) {
            throw wrong(END);
        }
        char c = text.charAt(at++);
        return switch (c) {
            case '"', '\\', '/' -> c;
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> hexadecimal();
            default -> throw wrong("Unknown escape \\" + c);
        };
    }

    private char hexadecimal() {
        if (at + 4 > text.length()) {
            throw wrong(END);
        }
        int code = 0;
        for (int end = at + 4; at < end; at++) {
            int digit = Character.digit(text.charAt(at), 16);
            if (digit < 0) {
                throw wrong("Not a hexadecimal digit");
            }
            code = code * 16 + digit;
        }
        return (char) code;
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) {
            throw wrong("Unexpected character '" + text.charAt(at) + "'");
        }
        at += word.length();
        return value;
    }

    private Object number() {
        Matcher matcher = NUMBER.matcher(text).region(at, text.length());
        if (!matcher.lookingAt()) {
            throw wrong("Unexpected character '" + text.charAt(at) + "'");
        }
        String literal = matcher.group();
        at = matcher.end();
        if (matcher.group(1) == null && matcher.group(2) == null && literal.length() < 19) {
            return Long.valueOf(literal);
        }
        return new BigDecimal(literal);
    }

    private void blanks() {
        while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
    }

    private boolean take(char c) {
        if (at < text.length() && text.charAt(at) == c) {
            at++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!take(c)) {
            throw wrong(at >= text.length() ? END : "Expected '" + c + "'");
        }
    }

    private IllegalArgumentException wrong(String what) {
        return new IllegalArgumentException(what + " at " + at);
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case Boolean flag -> out.append(flag);
            case Double real when !Double.isFinite(real) -> quote(real.toString(), out);
            case Float real when !Float.isFinite(real) -> quote(real.toString(), out);
            case Number number -> out.append(number);
            case Map<?, ?> members -> {
                out.append('{');
                String separator = "";
                for (Map.Entry<?, ?> member : members.entrySet()) {
                    out.append(separator);
                    quote(String.valueOf(member.getKey()), out);
                    out.append(':');
                    write(member.getValue(), out);
                    separator = ",";
                }
                out.append('}');
            }
            case Collection<?> elements -> {
                out.append('[');
                String separator = "";
                for (Object element : elements) {
                    out.append(separator);
                    write(element, out);
                    separator = ",";
                }
                out.append(']');
            }
            default -> quote(String.valueOf(value), out);
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        text.chars().forEach(c -> out.append(switch (c) {
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> c < ' ' ? String.format("\\u%04x", c) : String.valueOf((char) c);
        }));
        out.append('"');
    }
}
