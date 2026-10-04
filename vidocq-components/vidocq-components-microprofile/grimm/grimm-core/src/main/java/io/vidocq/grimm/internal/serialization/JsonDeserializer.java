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
import org.eclipse.microprofile.openapi.models.media.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonDeserializer {

    public OpenAPI deserialize(String json) {
        if (json == null) {
            throw new NullPointerException("json must not be null");
        }
        Object parsed = new Parser(json).parse();
        return OpenApiModelMapper.toOpenApi(parsed);
    }

    /**
     * Parses a JSON string into a raw object tree
     * ({@link Map}/{@link List}/{@link String}/{@link Number}/{@link Boolean}/{@code null}).
     * Exposed for callers that want to consume sub-trees (e.g. schema fragments).
     */
    public static Object parseRaw(String json) {
        if (json == null) {
            throw new NullPointerException("json must not be null");
        }
        return new Parser(json).parse();
    }

    /**
     * Maps a raw schema tree (as returned by {@link #parseRaw}) onto a {@link Schema} through the
     * same typed mapper as a static file, so a schema from configuration is read exactly like one
     * from {@code openapi.yaml}.
     */
    public static Schema toSchema(Object rawSchema) {
        return OpenApiModelMapper.toStaticSchema(rawSchema);
    }

    private static final class Parser {
        private final String source;
        private int index;

        private Parser(String source) {
            this.source = source;
        }

        private Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (index != source.length()) {
                throw new IllegalArgumentException("Unexpected trailing content in JSON");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= source.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }

            char c = source.charAt(index);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> {
                    if (c == '-' || Character.isDigit(c)) {
                        yield parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected token at position " + index);
                }
            };
        }

        private Map<String, Object> parseObject() {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek('}')) {
                index++;
                return result;
            }

            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            ArrayList<Object> result = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek(']')) {
                index++;
                return result;
            }

            while (true) {
                result.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    return result;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (index < source.length()) {
                char c = source.charAt(index++);
                if (c == '"') {
                    return value.toString();
                }
                if (c == '\\') {
                    if (index >= source.length()) {
                        throw new IllegalArgumentException("Invalid JSON escape sequence");
                    }
                    char escaped = source.charAt(index++);
                    switch (escaped) {
                        case '"' -> value.append('"');
                        case '\\' -> value.append('\\');
                        case '/' -> value.append('/');
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'u' -> value.append(parseUnicode());
                        default -> throw new IllegalArgumentException("Invalid JSON escape char: " + escaped);
                    }
                } else {
                    value.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private char parseUnicode() {
            if (index + 4 > source.length()) {
                throw new IllegalArgumentException("Invalid unicode escape in JSON string");
            }
            String hex = source.substring(index, index + 4);
            index += 4;
            return (char) Integer.parseInt(hex, 16);
        }

        private Object parseNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            while (index < source.length() && Character.isDigit(source.charAt(index))) {
                index++;
            }
            boolean decimal = false;
            if (peek('.')) {
                decimal = true;
                index++;
                while (index < source.length() && Character.isDigit(source.charAt(index))) {
                    index++;
                }
            }
            if (peek('e') || peek('E')) {
                decimal = true;
                index++;
                if (peek('+') || peek('-')) {
                    index++;
                }
                while (index < source.length() && Character.isDigit(source.charAt(index))) {
                    index++;
                }
            }

            String token = source.substring(start, index);
            if (decimal) {
                return Double.parseDouble(token);
            }
            return Long.parseLong(token);
        }

        private Object parseLiteral(String expected, Object value) {
            if (source.startsWith(expected, index)) {
                index += expected.length();
                return value;
            }
            throw new IllegalArgumentException("Unexpected token at position " + index);
        }

        private void skipWhitespace() {
            while (index < source.length()) {
                char c = source.charAt(index);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    index++;
                } else {
                    return;
                }
            }
        }

        private void expect(char expected) {
            if (index >= source.length() || source.charAt(index) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at position " + index);
            }
            index++;
        }

        private boolean peek(char c) {
            return index < source.length() && source.charAt(index) == c;
        }
    }
}

