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
package io.vidocq.runtime.devservices.extension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal recursive-descent reader for the dev services state file (spec §4.2). No JSON library: this module
 * depends only on {@code vidocq-runtime-spi} and {@code vidocq-runtime-devconsole-spi}.
 *
 * <p>It reads exactly what {@code StateFile.json} (in {@code vidocq-runtime-devservices-host}, which this module
 * never depends on) writes: objects, arrays, strings with the standard escapes (quote, backslash, slash, backspace,
 * form feed, newline, carriage return, tab, and a four-hex-digit U+XXXX code point), {@code true}, {@code false},
 * {@code null} and numbers, kept as text since nothing here computes with them. Anything else, or nesting past
 * 16 levels, throws {@link IllegalArgumentException}.
 */
public final class StateReader {

    private static final int MAX_DEPTH = 16;

    private StateReader() {}

    /**
     * Parses the dev services state file's content into a snapshot.
     *
     * @param json the file's content
     * @return the parsed snapshot
     * @throws IllegalArgumentException when {@code json} is not a well-formed object of the expected shape
     */
    public static DevServicesSnapshot parse(String json) {
        Cursor cursor = new Cursor(json);
        Object value = readValue(cursor, 0);
        cursor.skipWhitespace();
        if (cursor.pos != cursor.text.length()) {
            throw cursor.fail("trailing content");
        }
        if (!(value instanceof Map<?, ?> root)) {
            throw cursor.fail("expected an object");
        }
        return toSnapshot(root);
    }

    private static DevServicesSnapshot toSnapshot(Map<?, ?> root) {
        List<DevServicesSnapshot.Service> services = new ArrayList<>();
        if (root.get("services") instanceof List<?> list) {
            for (Object item : list) {
                services.add(toService(asObject(item, "a service")));
            }
        }
        List<DevServicesSnapshot.Skipped> skipped = new ArrayList<>();
        if (root.get("skipped") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> entry)) {
                    continue; // not an object: dropped, as an entry without an id is (#166)
                }
                String id = asString(entry.get("id"));
                if (id != null && !id.isBlank()) {
                    skipped.add(new DevServicesSnapshot.Skipped(id, asString(entry.get("reason"))));
                }
            }
        }
        return new DevServicesSnapshot(asString(root.get("host")), asString(root.get("state")),
                asString(root.get("startedAt")), services, skipped);
    }

    private static DevServicesSnapshot.Service toService(Map<?, ?> map) {
        Map<String, String> endpoints = new LinkedHashMap<>();
        if (map.get("endpoints") instanceof Map<?, ?> raw) {
            raw.forEach((key, value) -> endpoints.put(String.valueOf(key), asString(value)));
        }
        List<DevServicesSnapshot.Injected> injected = new ArrayList<>();
        if (map.get("injected") instanceof List<?> list) {
            for (Object item : list) {
                Map<?, ?> entry = asObject(item, "an injected entry");
                injected.add(new DevServicesSnapshot.Injected(asString(entry.get("key")), asString(entry.get("value")),
                        Boolean.TRUE.equals(entry.get("configured"))));
            }
        }
        return new DevServicesSnapshot.Service(asString(map.get("id")), asString(map.get("image")), endpoints,
                injected);
    }

    private static Map<?, ?> asObject(Object value, String what) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("dev services state: " + what + " must be an object");
        }
        return map;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // --- recursive-descent value reader ---

    private static Object readValue(Cursor cursor, int depth) {
        if (depth > MAX_DEPTH) {
            throw cursor.fail("nesting too deep");
        }
        cursor.skipWhitespace();
        char c = cursor.peek();
        return switch (c) {
            case '{' -> readObject(cursor, depth);
            case '[' -> readArray(cursor, depth);
            case '"' -> readString(cursor);
            case 't' -> readLiteral(cursor, "true", Boolean.TRUE);
            case 'f' -> readLiteral(cursor, "false", Boolean.FALSE);
            case 'n' -> readLiteral(cursor, "null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield readNumber(cursor);
                }
                throw cursor.fail("unexpected character");
            }
        };
    }

    private static Map<String, Object> readObject(Cursor cursor, int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        cursor.pos++;
        cursor.skipWhitespace();
        if (cursor.peek() == '}') {
            cursor.pos++;
            return map;
        }
        while (true) {
            cursor.skipWhitespace();
            if (cursor.peek() != '"') {
                throw cursor.fail("expected a string key");
            }
            String key = readString(cursor);
            cursor.skipWhitespace();
            cursor.expect(':');
            map.put(key, readValue(cursor, depth + 1));
            cursor.skipWhitespace();
            char next = cursor.next();
            if (next == '}') {
                return map;
            }
            if (next != ',') {
                throw cursor.fail("expected ',' or '}'");
            }
        }
    }

    private static List<Object> readArray(Cursor cursor, int depth) {
        List<Object> list = new ArrayList<>();
        cursor.pos++;
        cursor.skipWhitespace();
        if (cursor.peek() == ']') {
            cursor.pos++;
            return list;
        }
        while (true) {
            list.add(readValue(cursor, depth + 1));
            cursor.skipWhitespace();
            char next = cursor.next();
            if (next == ']') {
                return list;
            }
            if (next != ',') {
                throw cursor.fail("expected ',' or ']'");
            }
        }
    }

    private static String readString(Cursor cursor) {
        cursor.pos++;
        StringBuilder text = new StringBuilder();
        while (true) {
            if (cursor.pos >= cursor.text.length()) {
                throw cursor.fail("unterminated string");
            }
            char c = cursor.text.charAt(cursor.pos++);
            if (c == '"') {
                return text.toString();
            }
            if (c != '\\') {
                if (c < 0x20) {
                    throw cursor.fail("control character in string");
                }
                text.append(c);
                continue;
            }
            if (cursor.pos >= cursor.text.length()) {
                throw cursor.fail("unterminated escape");
            }
            char escaped = cursor.text.charAt(cursor.pos++);
            switch (escaped) {
                case '"' -> text.append('"');
                case '\\' -> text.append('\\');
                case '/' -> text.append('/');
                case 'b' -> text.append('\b');
                case 'f' -> text.append('\f');
                case 'n' -> text.append('\n');
                case 'r' -> text.append('\r');
                case 't' -> text.append('\t');
                case 'u' -> text.append(readUnicodeEscape(cursor));
                default -> throw cursor.fail("invalid escape");
            }
        }
    }

    private static char readUnicodeEscape(Cursor cursor) {
        if (cursor.pos + 4 > cursor.text.length()) {
            throw cursor.fail("invalid unicode escape");
        }
        String hex = cursor.text.substring(cursor.pos, cursor.pos + 4);
        try {
            char value = (char) Integer.parseInt(hex, 16);
            cursor.pos += 4;
            return value;
        } catch (NumberFormatException e) {
            throw cursor.fail("invalid unicode escape");
        }
    }

    private static String readNumber(Cursor cursor) {
        int start = cursor.pos;
        if (cursor.peek() == '-') {
            cursor.pos++;
        }
        while (cursor.pos < cursor.text.length() && isNumberChar(cursor.text.charAt(cursor.pos))) {
            cursor.pos++;
        }
        if (cursor.pos == start) {
            throw cursor.fail("invalid number");
        }
        return cursor.text.substring(start, cursor.pos);
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
    }

    private static Object readLiteral(Cursor cursor, String literal, Object value) {
        if (!cursor.text.startsWith(literal, cursor.pos)) {
            throw cursor.fail("expected '" + literal + "'");
        }
        cursor.pos += literal.length();
        return value;
    }

    /** The position in the text being read, advanced by every read; the only mutable state of this reader. */
    private static final class Cursor {

        final String text;
        int pos;

        Cursor(String text) {
            this.text = text == null ? "" : text;
        }

        char peek() {
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        char next() {
            if (pos >= text.length()) {
                throw fail("unexpected end of input");
            }
            return text.charAt(pos++);
        }

        void expect(char c) {
            if (peek() != c) {
                throw fail("expected '" + c + "'");
            }
            pos++;
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        IllegalArgumentException fail(String what) {
            return new IllegalArgumentException("dev services state: " + what + " at " + pos);
        }
    }
}
