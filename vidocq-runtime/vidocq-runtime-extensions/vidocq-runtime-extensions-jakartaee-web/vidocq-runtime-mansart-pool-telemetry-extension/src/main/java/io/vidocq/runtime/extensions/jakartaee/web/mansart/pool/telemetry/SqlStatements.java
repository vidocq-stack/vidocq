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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.telemetry;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a span says of the SQL it times, per the OpenTelemetry database conventions: the operation, the table
 * ({@code db.collection.name}), the span name, and a plain statement's text without its literals.
 */
final class SqlStatements {

    /** The operation and table of a statement; empty strings when the SQL does not tell. */
    record Parsed(String operation, String collection) {}

    private static final Pattern FIRST_WORD = Pattern.compile("^\\s*([A-Za-z]+)");
    private static final Pattern FROM = Pattern.compile("(?i)\\bfrom\\s+(" + Identifier.PATTERN + ")");
    private static final Pattern INTO = Pattern.compile("(?i)\\binto\\s+(" + Identifier.PATTERN + ")");
    private static final Pattern UPDATE = Pattern.compile("(?i)^\\s*update\\s+(" + Identifier.PATTERN + ")");
    private static final Set<String> WITH_TABLE = Set.of("SELECT", "DELETE", "INSERT", "UPDATE", "MERGE");

    private SqlStatements() {}

    private static final class Identifier {
        /** A possibly qualified, possibly double-quoted name: {@code books}, {@code public.books}, {@code "Loans"}. */
        static final String PATTERN = "(?:\"[^\"]+\"|[\\w$]+)(?:\\.(?:\"[^\"]+\"|[\\w$]+))*";
    }

    static Parsed parse(String sql) {
        if (sql == null) {
            return new Parsed("", "");
        }
        Matcher first = FIRST_WORD.matcher(sql);
        if (!first.find()) {
            return new Parsed("", "");
        }
        String operation = first.group(1).toUpperCase(Locale.ROOT);
        if (!WITH_TABLE.contains(operation)) {
            return new Parsed(operation, "");
        }
        Matcher table = switch (operation) {
            case "INSERT", "MERGE" -> INTO.matcher(sql);
            case "UPDATE" -> UPDATE.matcher(sql);
            default -> FROM.matcher(sql);
        };
        return new Parsed(operation, table.find() ? table.group(1).replace("\"", "") : "");
    }

    /** {@code <operation> <table>}, else the operation, else the database system, as the conventions ask. */
    static String spanName(Parsed parsed, String system) {
        if (parsed.operation().isEmpty()) {
            return system;
        }
        return parsed.collection().isEmpty() ? parsed.operation() : parsed.operation() + " " + parsed.collection();
    }

    /**
     * A plain statement's SQL with each literal replaced by {@code ?}: no value of the application leaks. A literal is
     * a quoted string ({@code ''} escapes a quote) or a number not part of an identifier. A scan, not a regular
     * expression: a long string literal would overflow the stack of a backtracking one.
     */
    static String sanitize(String sql) {
        if (sql == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            int end = literalEnd(sql, i);
            if (end > i) {
                out.append('?');
                i = end;
            } else {
                out.append(sql.charAt(i++));
            }
        }
        return out.toString();
    }

    /** The end of the literal starting at {@code at}, else {@code at}. */
    private static int literalEnd(String sql, int at) {
        char c = sql.charAt(at);
        if (c == '\'') {
            return stringEnd(sql, at);
        }
        if (at > 0 && partOfName(sql.charAt(at - 1))) {
            return at;
        }
        if (c == '-' && at + 1 < sql.length() && isDigit(sql.charAt(at + 1))) {
            int end = numberEnd(sql, at + 1);
            return end > at + 1 ? end : at;
        }
        return isDigit(c) ? numberEnd(sql, at) : at;
    }

    /**
     * The end of the quoted string at {@code at}. Unterminated, the string ends at the last quote of an
     * {@code ''} pair, if any, as a backtracking match would.
     */
    private static int stringEnd(String sql, int at) {
        int lastPair = -1;
        int i = at + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) != '\'') {
                i++;
            } else if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                lastPair = i;
                i += 2;
            } else {
                return i + 1;
            }
        }
        return lastPair < 0 ? at : lastPair + 1;
    }

    /**
     * The end of the number whose digits start at {@code at}: digits, an optional fraction, an optional exponent, not
     * followed by a character of a name, a dot or a dangling part; else {@code at}.
     */
    private static int numberEnd(String sql, int at) {
        int i = digitsEnd(sql, at);
        if (i < sql.length() && sql.charAt(i) == '.') {
            int fraction = digitsEnd(sql, i + 1);
            if (fraction == i + 1) {
                return at;
            }
            i = fraction;
        }
        if (i < sql.length() && (sql.charAt(i) == 'e' || sql.charAt(i) == 'E')) {
            int sign = i + 1 < sql.length() && (sql.charAt(i + 1) == '+' || sql.charAt(i + 1) == '-') ? i + 2 : i + 1;
            int exponent = digitsEnd(sql, sign);
            if (exponent == sign) {
                return at;
            }
            i = exponent;
        }
        return i < sql.length() && partOfName(sql.charAt(i)) ? at : i;
    }

    private static int digitsEnd(String sql, int at) {
        int i = at;
        while (i < sql.length() && isDigit(sql.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** {@code [\\w.$]}: a character that makes a digit part of a name or of something else than a number. */
    private static boolean partOfName(char c) {
        return c == '.' || c == '$' || c == '_' || isDigit(c) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
