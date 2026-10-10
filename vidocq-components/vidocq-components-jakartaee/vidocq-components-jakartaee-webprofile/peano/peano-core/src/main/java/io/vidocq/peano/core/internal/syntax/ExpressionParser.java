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
package io.vidocq.peano.core.internal.syntax;

import java.util.ArrayList;
import java.util.Objects;
import jakarta.el.ELException;

/** Parses literal text and expression delimiters before delegating bodies to {@link Parser}. */
public final class ExpressionParser {
    private ExpressionParser() {
    }

    /**
     * Parses a complete expression, preserving literal text and expression order.
     *
     * @param source original expression source
     * @return immutable template
     * @throws ELException for malformed, nested or mixed expression delimiters
     */
    public static Template parse(String source) {
        Objects.requireNonNull(source, "source");
        var parts = new ArrayList<Template.Part>();
        var text = new StringBuilder();
        Template.Kind kind = Template.Kind.LITERAL;
        int cursor = 0;
        while (cursor < source.length()) {
            char ch = source.charAt(cursor);
            if (ch == '\\' && cursor + 1 < source.length() && source.charAt(cursor + 1) == '\\') {
                text.append('\\');
                cursor += 2;
            } else if (ch == '\\' && markerAt(source, cursor + 1)) {
                text.append(source.charAt(cursor + 1)).append('{');
                cursor += 3;
            } else if (markerAt(source, cursor)) {
                Template.Kind next = ch == '$' ? Template.Kind.IMMEDIATE : Template.Kind.DEFERRED;
                if (kind != Template.Kind.LITERAL && kind != next) {
                    throw error("Mixed immediate and deferred expressions", cursor);
                }
                kind = next;
                if (!text.isEmpty()) {
                    parts.add(new Template.Text(text.toString()));
                    text.setLength(0);
                }
                int end = bodyEnd(source, cursor);
                parts.add(new Template.Evaluation(Parser.parseBody(
                        source.substring(cursor + 2, end), cursor + 2)));
                cursor = end + 1;
            } else {
                text.append(ch);
                cursor++;
            }
        }
        if (!text.isEmpty() || parts.isEmpty()) {
            parts.add(new Template.Text(text.toString()));
        }
        return new Template(kind, parts);
    }

    private static int bodyEnd(String source, int start) {
        int depth = 0;
        char quote = 0;
        int quoteStart = -1;
        for (int cursor = start + 2; cursor < source.length(); cursor++) {
            char ch = source.charAt(cursor);
            if (quote != 0) {
                if (ch == '\\') {
                    cursor++;
                } else if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                quoteStart = cursor;
            } else if (markerAt(source, cursor)) {
                throw error("Nested eval-expression", cursor);
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                if (depth == 0) {
                    return cursor;
                }
                depth--;
            }
        }
        if (quote != 0) {
            throw error("Unterminated string", quoteStart);
        }
        throw error("Unterminated eval-expression", start);
    }

    private static boolean markerAt(String source, int offset) {
        return offset + 1 < source.length()
                && (source.charAt(offset) == '$' || source.charAt(offset) == '#')
                && source.charAt(offset + 1) == '{';
    }

    private static ELException error(String message, int offset) {
        return new ELException(message + " at offset " + offset);
    }
}

