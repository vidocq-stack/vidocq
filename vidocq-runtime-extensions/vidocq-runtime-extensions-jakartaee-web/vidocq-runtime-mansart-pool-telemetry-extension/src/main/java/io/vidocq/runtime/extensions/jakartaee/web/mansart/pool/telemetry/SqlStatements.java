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

    /** A string literal ({@code ''} escapes a quote) or a number not part of an identifier. */
    private static final Pattern LITERAL =
            Pattern.compile("'(?:[^']|'')*'|(?<![\\w.$])-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?(?![\\w.$])");

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

    /** A plain statement's SQL with each literal replaced by {@code ?}: no value of the application leaks. */
    static String sanitize(String sql) {
        return sql == null ? null : LITERAL.matcher(sql).replaceAll("?");
    }
}
