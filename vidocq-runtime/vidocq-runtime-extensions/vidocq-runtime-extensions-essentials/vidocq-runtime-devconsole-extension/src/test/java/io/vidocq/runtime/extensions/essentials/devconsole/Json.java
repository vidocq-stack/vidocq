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

/**
 * A strict RFC 8259 reader for the tests, so that they check the console's documents as a browser's
 * {@code JSON.parse} reads them, and fail on a document it would reject: an object is a {@link Map} in document
 * order, an array a {@link List}, a number a {@link Long} when it is written as an integer, a {@link Double}
 * otherwise.
 */
final class Json {

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /** The value {@code text} holds, the whole of it. */
    static Object parse(String text) {
        Json json = new Json(text);
        Object value = json.value();
        json.blanks();
        if (json.at != text.length()) {
            throw json.error("text after the value");
        }
        return value;
    }

    /** The object {@code text} holds. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        return (Map<String, Object>) parse(text);
    }

    /** The array of objects {@code text} holds. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> array(String text) {
        return (List<Map<String, Object>>) parse(text);
    }

    private Object value() {
        blanks();
        if (at >= text.length()) {
            throw error("end of text");
        }
        char c = text.charAt(at);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
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
                throw error("a name");
            }
            String name = string();
            if (object.containsKey(name)) {
                throw error("the name '" + name + "' twice");
            }
            blanks();
            expect(':');
            object.put(name, value());
            blanks();
            if (peek() == ',') {
                at++;
            } else {
                expect('}');
                return object;
            }
        }
    }

    private List<Object> array() {
        List<Object> array = new ArrayList<>();
        at++;
        blanks();
        if (peek() == ']') {
            at++;
            return array;
        }
        while (true) {
            array.add(value());
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
            if (at >= text.length()) {
                throw error("an unterminated string");
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw error("a raw control character in a string");
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escape = text.charAt(at++);
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> throw error("the escape \\" + escape);
            }
        }
    }

    private Object number() {
        int start = at;
        if (peek() == '-') {
            at++;
        }
        boolean integer = true;
        while (at < text.length() && "0123456789.eE+-".indexOf(text.charAt(at)) >= 0) {
            integer &= Character.isDigit(text.charAt(at));
            at++;
        }
        String number = text.substring(start, at);
        if (number.isEmpty() || number.equals("-")) {
            throw error("a value");
        }
        if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
            throw error("the number " + number);
        }
        return integer ? (Object) Long.parseLong(number) : (Object) Double.parseDouble(number);
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) {
            throw error("a value");
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
        return at < text.length() ? text.charAt(at) : '\0';
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("'" + c + "'");
        }
        at++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("invalid JSON at " + at + ": " + what + " in "
                + text.substring(Math.max(0, at - 40), Math.min(text.length(), at + 40)));
    }
}
