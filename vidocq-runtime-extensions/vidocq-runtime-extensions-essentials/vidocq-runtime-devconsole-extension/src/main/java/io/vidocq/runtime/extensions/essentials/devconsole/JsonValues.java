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
package io.vidocq.runtime.extensions.essentials.devconsole;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads any JSON value (RFC 8259) and writes one back, by hand, for the {@linkplain DevMcp dev MCP}: an object is a
 * {@link Map} in document order, an array a {@link List}, a string a {@link String}, a number a {@link Long} when it
 * is written as an integer that fits one, a {@link Double} otherwise, {@code true} and {@code false} a
 * {@link Boolean}, and {@code null} is {@code null}.
 *
 * <p>The reader is strict: a name written twice in one object, text after the value, a raw control character in a
 * string, a number JSON does not allow, or values nested deeper than the limit it is given are refused, with an
 * {@link IllegalArgumentException} that says where and never quotes the text.
 */
final class JsonValues {

    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private final String text;
    private final int maxDepth;
    private int at;

    private JsonValues(String text, int maxDepth) {
        this.text = text;
        this.maxDepth = maxDepth;
    }

    /**
     * The value {@code text} holds, the whole of it.
     *
     * @param text     the text
     * @param maxDepth how deep objects and arrays may nest, {@code 1} for one object of scalars
     * @return the value
     * @throws IllegalArgumentException when {@code text} is not one JSON value within that depth
     */
    static Object parse(String text, int maxDepth) {
        JsonValues json = new JsonValues(text, maxDepth);
        Object value = json.value(0);
        json.blanks();
        if (json.at != text.length()) {
            throw json.error("text after the value");
        }
        return value;
    }

    /**
     * Writes {@code value}, as {@link #parse} reads it, to {@code out}.
     *
     * @param out   the writer, where a value may come next
     * @param value a {@link Map} of names to values, a {@link List}, a {@link String}, a {@link Number}, a
     *              {@link Boolean} or {@code null}
     */
    static void write(JsonWriter out, Object value) {
        switch (value) {
            case null -> out.nullValue();
            case Map<?, ?> object -> {
                out.beginObject();
                object.forEach((name, member) -> {
                    out.name((String) name);
                    write(out, member);
                });
                out.endObject();
            }
            case List<?> array -> {
                out.beginArray();
                array.forEach(item -> write(out, item));
                out.endArray();
            }
            case String string -> out.value(string);
            case Boolean bool -> out.value(bool.booleanValue());
            case Long whole -> out.value(whole.longValue());
            case Integer whole -> out.value(whole.longValue());
            case Number number -> out.value(number.doubleValue());
            default -> throw new IllegalArgumentException("not a JSON value: " + value.getClass().getName());
        }
    }

    /**
     * {@code value} as indented JSON text, two spaces a level, for a reader: an empty object or array on one line.
     *
     * @param value as for {@link #write}
     * @return the text
     */
    static String pretty(Object value) {
        StringBuilder out = new StringBuilder(4096);
        pretty(out, value, 0);
        return out.toString();
    }

    private static void pretty(StringBuilder out, Object value, int level) {
        if (value instanceof Map<?, ?> object && !object.isEmpty()) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> member : object.entrySet()) {
                out.append(first ? "\n" : ",\n").append("  ".repeat(level + 1));
                first = false;
                out.append(scalar(member.getKey())).append(": ");
                pretty(out, member.getValue(), level + 1);
            }
            out.append('\n').append("  ".repeat(level)).append('}');
        } else if (value instanceof List<?> array && !array.isEmpty()) {
            out.append('[');
            boolean first = true;
            for (Object item : array) {
                out.append(first ? "\n" : ",\n").append("  ".repeat(level + 1));
                first = false;
                pretty(out, item, level + 1);
            }
            out.append('\n').append("  ".repeat(level)).append(']');
        } else {
            out.append(scalar(value));
        }
    }

    /** A scalar, an empty object or an empty array, as {@link JsonWriter} writes it. */
    private static String scalar(Object value) {
        JsonWriter out = new JsonWriter();
        write(out, value);
        return out.toString();
    }

    private Object value(int depth) {
        blanks();
        char c = peek();
        return switch (c) {
            case '{' -> object(depth + 1);
            case '[' -> array(depth + 1);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        if (depth > maxDepth) {
            throw error("values nested deeper than " + maxDepth);
        }
        Map<String, Object> object = new LinkedHashMap<>();
        at++;
        blanks();
        if (peek() == '}') {
            at++;
            return object;
        }
        while (true) {
            blanks();
            if (peek() != '"') {
                throw error("a name was expected");
            }
            String name = string();
            if (object.containsKey(name)) {
                throw error("a name written twice");
            }
            blanks();
            expect(':');
            object.put(name, value(depth));
            blanks();
            if (peek() == ',') {
                at++;
            } else {
                expect('}');
                return object;
            }
        }
    }

    private List<Object> array(int depth) {
        if (depth > maxDepth) {
            throw error("values nested deeper than " + maxDepth);
        }
        List<Object> array = new ArrayList<>();
        at++;
        blanks();
        if (peek() == ']') {
            at++;
            return array;
        }
        while (true) {
            array.add(value(depth));
            blanks();
            if (peek() == ',') {
                at++;
            } else {
                expect(']');
                return array;
            }
        }
    }

    private String string() {
        at++;
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw error("a control character in a string");
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escaped = next();
            switch (escaped) {
                case '"', '\\', '/' -> out.append(escaped);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        int digit = Character.digit(next(), 16);
                        if (digit < 0) {
                            throw error("a \\u escape that is not hexadecimal");
                        }
                        code = code * 16 + digit;
                    }
                    out.append((char) code);
                }
                default -> throw error("an unknown escape");
            }
        }
    }

    private Object number() {
        int start = at;
        while (at < text.length() && "-+0123456789.eE".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        String number = text.substring(start, at);
        if (!NUMBER.matcher(number).matches()) {
            at = start;
            throw error("a value was expected");
        }
        if (number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0) {
            try {
                return Long.parseLong(number);
            } catch (NumberFormatException tooLarge) {
                // an integer past a long: a double, as JavaScript would read it
            }
        }
        return Double.parseDouble(number);
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) {
            throw error("a value was expected");
        }
        at += word.length();
        return value;
    }

    private void blanks() {
        while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
    }

    private char peek() {
        if (at >= text.length()) {
            throw error("end of text");
        }
        return text.charAt(at);
    }

    private char next() {
        char c = peek();
        at++;
        return c;
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("'" + c + "' was expected");
        }
        at++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " at " + at);
    }
}
