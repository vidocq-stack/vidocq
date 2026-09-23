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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the body of an action request: one JSON object (RFC 8259) whose values are all strings, such as
 * {@code {"logger":"com.acme","level":"DEBUG"}}, and nothing else. A nested object, an array, a number, a
 * {@code null}, a key written twice or text after the object is refused: the console never guesses what a request
 * meant.
 */
final class JsonStrings {

    private final String text;
    private int at;

    private JsonStrings(String text) {
        this.text = text;
    }

    /**
     * The members of the object {@code text} holds, in document order.
     *
     * @param text the whole body
     * @return the keys and their string values
     * @throws IllegalArgumentException when {@code text} is not one object of strings
     */
    static Map<String, String> object(String text) {
        JsonStrings json = new JsonStrings(text);
        Map<String, String> members = new LinkedHashMap<>();
        json.blanks();
        json.expect('{');
        json.blanks();
        if (json.peek() == '}') {
            json.at++;
        } else {
            while (true) {
                json.blanks();
                String key = json.string();
                json.blanks();
                json.expect(':');
                json.blanks();
                String value = json.string();
                if (members.putIfAbsent(key, value) != null) {
                    throw new IllegalArgumentException("key \"" + Texts.clean(key) + "\" written twice");
                }
                json.blanks();
                char next = json.next();
                if (next == '}') {
                    break;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("expected , or } at " + (json.at - 1));
                }
            }
        }
        json.blanks();
        if (json.at != text.length()) {
            throw new IllegalArgumentException("text after the object");
        }
        return members;
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
        if (next() != c) {
            throw new IllegalArgumentException("expected " + c + " at " + (at - 1));
        }
    }

    private void blanks() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            at++;
        }
    }

    private String string() {
        if (next() != '"') {
            throw new IllegalArgumentException("a string was expected at " + (at - 1));
        }
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
                    if (at + 4 > text.length()) {
                        throw new IllegalArgumentException("end of text");
                    }
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        int digit = Character.digit(text.charAt(at++), 16);
                        if (digit < 0) {
                            throw new IllegalArgumentException("a \\u escape that is not hexadecimal");
                        }
                        code = code * 16 + digit;
                    }
                    out.append((char) code);
                }
                default -> throw new IllegalArgumentException("an unknown escape \\" + escaped);
            }
        }
    }
}
