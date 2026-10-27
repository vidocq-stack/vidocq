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
package io.vidocq.runtime.spi.devconsole;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether a text is one JSON object (RFC 8259), checked by hand: this module depends on no JSON library. As strict
 * as the console's own reader, so that whatever this accepts the console can read: a name written twice in one
 * object, text after the object, a raw control character in a string, a number JSON does not allow, or values
 * nested deeper than the limit are refused.
 */
final class JsonCheck {

    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private final String text;
    private final int maxDepth;
    private int at;

    private JsonCheck(String text, int maxDepth) {
        this.text = text;
        this.maxDepth = maxDepth;
    }

    /**
     * Whether {@code text} is one JSON object within {@code maxDepth}, and nothing else.
     *
     * @param text     the text, or {@code null}
     * @param maxDepth how deep objects and arrays may nest, {@code 1} for one object of scalars
     * @return {@code true} when it is
     */
    static boolean isObject(String text, int maxDepth) {
        if (text == null) {
            return false;
        }
        JsonCheck json = new JsonCheck(text, maxDepth);
        try {
            json.blanks();
            if (json.peek() != '{') {
                return false;
            }
            json.value(0);
            json.blanks();
            return json.at == text.length();
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private void value(int depth) {
        blanks();
        switch (peek()) {
            case '{' -> object(depth + 1);
            case '[' -> array(depth + 1);
            case '"' -> string();
            case 't' -> literal("true");
            case 'f' -> literal("false");
            case 'n' -> literal("null");
            default -> number();
        }
    }

    private void object(int depth) {
        if (depth > maxDepth) {
            throw new IllegalArgumentException("nested too deep");
        }
        Set<String> names = new HashSet<>();
        at++;
        blanks();
        if (peek() == '}') {
            at++;
            return;
        }
        while (true) {
            blanks();
            if (peek() != '"' || !names.add(string())) {
                throw new IllegalArgumentException("a name was expected, once");
            }
            blanks();
            expect(':');
            value(depth);
            blanks();
            if (peek() == ',') {
                at++;
            } else {
                expect('}');
                return;
            }
        }
    }

    private void array(int depth) {
        if (depth > maxDepth) {
            throw new IllegalArgumentException("nested too deep");
        }
        at++;
        blanks();
        if (peek() == ']') {
            at++;
            return;
        }
        while (true) {
            value(depth);
            blanks();
            if (peek() == ',') {
                at++;
            } else {
                expect(']');
                return;
            }
        }
    }

    /** Reads a string and returns it decoded, so that two spellings of one name count as the same name. */
    private String string() {
        at++;
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw new IllegalArgumentException("a control character in a string");
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
                            throw new IllegalArgumentException("a \\u escape that is not hexadecimal");
                        }
                        code = code * 16 + digit;
                    }
                    out.append((char) code);
                }
                default -> throw new IllegalArgumentException("an unknown escape");
            }
        }
    }

    private void number() {
        int start = at;
        while (at < text.length() && "-+0123456789.eE".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        if (!NUMBER.matcher(text.substring(start, at)).matches()) {
            throw new IllegalArgumentException("a value was expected");
        }
    }

    private void literal(String word) {
        if (!text.startsWith(word, at)) {
            throw new IllegalArgumentException("a value was expected");
        }
        at += word.length();
    }

    private void blanks() {
        while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
    }

    private char peek() {
        if (at >= text.length()) {
            throw new IllegalArgumentException("end of text");
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
            throw new IllegalArgumentException("'" + c + "' was expected");
        }
        at++;
    }
}
