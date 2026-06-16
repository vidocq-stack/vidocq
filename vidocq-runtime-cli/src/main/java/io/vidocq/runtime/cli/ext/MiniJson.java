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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny, dependency-free JSON reader scoped to the registry schema: a top-level
 * array of flat objects. Scalar values (string/number/boolean/null) are exposed
 * as strings; nested arrays/objects are skipped. Not a general-purpose parser,
 * but enough to read {@code registry-cache.json} without pulling in Jackson.
 */
public final class MiniJson {

    private final String src;
    private int pos;

    private MiniJson(String src) {
        this.src = src;
    }

    /**
     * Parses a JSON array of objects into a list of string-valued maps.
     *
     * @throws IllegalArgumentException on malformed input
     */
    public static List<Map<String, String>> parseArrayOfObjects(String json) {
        if (json == null) {
            throw new IllegalArgumentException("json must not be null");
        }
        MiniJson p = new MiniJson(json);
        p.skipWs();
        List<Map<String, String>> result = p.readArrayOfObjects();
        p.skipWs();
        if (p.pos != p.src.length()) {
            throw new IllegalArgumentException("Trailing content after JSON array at index " + p.pos);
        }
        return result;
    }

    private List<Map<String, String>> readArrayOfObjects() {
        expect('[');
        List<Map<String, String>> list = new ArrayList<>();
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(readObject());
            skipWs();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw err("',' or ']'");
            }
        }
    }

    private Map<String, String> readObject() {
        expect('{');
        Map<String, String> obj = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            pos++;
            return obj;
        }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            expect(':');
            skipWs();
            String value = readValue();
            obj.put(key, value);
            skipWs();
            char c = next();
            if (c == '}') {
                return obj;
            }
            if (c != ',') {
                throw err("',' or '}'");
            }
        }
    }

    /** Reads a scalar value as a string; skips nested arrays/objects (stored as null). */
    private String readValue() {
        char c = peek();
        return switch (c) {
            case '"' -> readString();
            case '{' -> { skipContainer('{', '}'); yield null; }
            case '[' -> { skipContainer('[', ']'); yield null; }
            default -> readLiteral();
        };
    }

    private String readLiteral() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) {
                break;
            }
            pos++;
        }
        if (pos == start) {
            throw err("a value");
        }
        String token = src.substring(start, pos);
        return "null".equals(token) ? null : token;
    }

    private void skipContainer(char open, char close) {
        expect(open);
        int depth = 1;
        while (pos < src.length() && depth > 0) {
            char c = src.charAt(pos++);
            if (c == '"') {
                pos--;
                readString();
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
            }
        }
        if (depth != 0) {
            throw err("matching '" + close + "'");
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= src.length()) {
                    break;
                }
                char e = src.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) {
                            throw err("4 hex digits");
                        }
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw err("a valid escape");
                }
            } else {
                sb.append(c);
            }
        }
        throw err("closing '\"'");
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw err("'" + c + "'");
        }
        pos++;
    }

    private char peek() {
        if (pos >= src.length()) {
            throw err("more input");
        }
        return src.charAt(pos);
    }

    private char next() {
        if (pos >= src.length()) {
            throw err("more input");
        }
        return src.charAt(pos++);
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private IllegalArgumentException err(String expected) {
        return new IllegalArgumentException("Malformed JSON: expected " + expected + " at index " + pos);
    }
}
