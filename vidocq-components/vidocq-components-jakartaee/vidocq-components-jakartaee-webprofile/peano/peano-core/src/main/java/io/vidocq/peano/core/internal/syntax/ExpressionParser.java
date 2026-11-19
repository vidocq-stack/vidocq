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
import java.util.List;
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
                kind = evaluationKind(kind, ch, cursor);
                flushText(text, parts);
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

    /** The template kind once the delimiter at {@code cursor} is read: immediate and deferred never mix. */
    private static Template.Kind evaluationKind(Template.Kind current, char marker, int cursor) {
        Template.Kind next = marker == '$' ? Template.Kind.IMMEDIATE : Template.Kind.DEFERRED;
        if (current != Template.Kind.LITERAL && current != next) {
            throw error("Mixed immediate and deferred expressions", cursor);
        }
        return next;
    }

    private static void flushText(StringBuilder text, List<Template.Part> parts) {
        if (!text.isEmpty()) {
            parts.add(new Template.Text(text.toString()));
            text.setLength(0);
        }
    }

    private static int bodyEnd(String source, int start) {
        int depth = 0;
        int cursor = start + 2;
        while (cursor < source.length()) {
            char ch = source.charAt(cursor);
            if (ch == '\'' || ch == '"') {
                cursor = quoteEnd(source, cursor);
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
            cursor++;
        }
        throw error("Unterminated eval-expression", start);
    }

    /** The offset of the quote closing the string literal opened at {@code start}; escapes skip a character. */
    private static int quoteEnd(String source, int start) {
        char quote = source.charAt(start);
        int cursor = start + 1;
        while (cursor < source.length()) {
            char ch = source.charAt(cursor);
            if (ch == '\\') {
                cursor += 2;
            } else if (ch == quote) {
                return cursor;
            } else {
                cursor++;
            }
        }
        throw error("Unterminated string", start);
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

