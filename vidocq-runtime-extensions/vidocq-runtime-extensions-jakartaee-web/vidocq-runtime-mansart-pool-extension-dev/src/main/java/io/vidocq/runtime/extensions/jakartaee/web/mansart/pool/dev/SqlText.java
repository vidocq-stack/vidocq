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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the pools panel reads of a SQL text before it runs it (SQL spec §4.2), without parsing it: its first word,
 * whether it holds more than one statement, and its named parameters. Each is read in the text's code only, outside
 * strings ({@code '…'}, a doubled quote inside), quoted identifiers (between the database's identifier quotes, doubled
 * inside) and comments ({@code --} to the end of the line, and between {@code /*} and its end), which may run over
 * several lines; an unterminated one runs to the end of the text.
 */
final class SqlText {

    /** The first words of a statement that only reads: what Query runs. */
    static final Set<String> READS = Set.of("SELECT", "WITH", "VALUES", "SHOW", "EXPLAIN", "TABLE");

    /**
     * A text ready to run.
     *
     * @param sql   the text, each named parameter {@code :name} replaced by {@code ?}
     * @param names the parameters' names, one per {@code ?}, in the order of the text
     */
    record Named(String sql, List<String> names) {}

    private SqlText() {}

    /**
     * The first word of {@code sql} in capitals, blanks, comments and opening parentheses skipped; empty when the code
     * starts with no letter.
     */
    static String firstWord(String sql, char identifierQuote) {
        boolean[] code = code(sql, identifierQuote);
        int i = 0;
        while (i < sql.length() && (!code[i] || Character.isWhitespace(sql.charAt(i)) || sql.charAt(i) == '(')) {
            i++;
        }
        int end = i;
        while (end < sql.length() && code[end] && Character.isLetter(sql.charAt(end))) {
            end++;
        }
        return sql.substring(i, end).toUpperCase(Locale.ROOT);
    }

    /** Whether {@code sql} holds more than one statement: a {@code ;} of its code that is not the last of it. */
    static boolean severalStatements(String sql, char identifierQuote) {
        boolean[] code = code(sql, identifierQuote);
        int last = sql.length() - 1;
        while (last >= 0 && (!code[last] || Character.isWhitespace(sql.charAt(last)))) {
            last--;
        }
        for (int i = 0; i < last; i++) {
            if (code[i] && sql.charAt(i) == ';') {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code sql} with each named parameter of its code, {@code :name} (a letter or {@code _}, then letters, digits
     * and {@code _}), replaced by {@code ?}; a cast, {@code ::type}, is none.
     */
    static Named named(String sql, char identifierQuote) {
        boolean[] code = code(sql, identifierQuote);
        StringBuilder out = new StringBuilder(sql.length());
        List<String> names = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            boolean parameter = code[i] && c == ':' && i + 1 < sql.length() && code[i + 1]
                    && (Character.isLetter(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')
                    && (i == 0 || sql.charAt(i - 1) != ':');
            if (!parameter) {
                out.append(c);
                i++;
                continue;
            }
            int end = i + 1;
            while (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
                end++;
            }
            names.add(sql.substring(i + 1, end));
            out.append('?');
            i = end;
        }
        return new Named(out.toString(), List.copyOf(names));
    }

    /** Which characters of {@code sql} are code: neither in a string, nor in a quoted identifier, nor in a comment. */
    private static boolean[] code(String sql, char identifierQuote) {
        int n = sql.length();
        boolean[] code = new boolean[n];
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : 0;
            if (c == '\'' || c == identifierQuote) {
                i++;
                while (i < n && (sql.charAt(i) != c || (i + 1 < n && sql.charAt(i + 1) == c))) {
                    i += sql.charAt(i) == c ? 2 : 1;
                }
                i++;
            } else if (c == '-' && next == '-') {
                while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else {
                code[i++] = true;
            }
        }
        return code;
    }
}
