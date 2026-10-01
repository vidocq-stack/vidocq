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

import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev.PoolMetadata.Column;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev.PoolMetadata.Table;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;

import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code sql-<pool>} language of a pool (SQL spec §4.3), which the page's SQL editors of its Query and Execute
 * fetch once per boot: SQL's dialect — SQL-92's keywords and the product's, the common functions, SQL's clauses,
 * aliases, the database's identifier quote and how it stores a name written without quotes — and one target per table
 * or view the boot listed, named bare in the current schema and {@code schema.name} in another, with its columns. A
 * column's type is its JSON Schema type, from its {@link Types} code; a column of a single-column foreign key names
 * the table it refers to as its {@code target}. Built from what the boot read: no query.
 */
final class SqlLanguage {

    /** SQL-92's reserved words, and the few of SQL:2003 a query of today writes, which are no column. */
    static final List<String> KEYWORDS = List.of("ABSOLUTE", "ACTION", "ADD", "ALL", "ALLOCATE", "ALTER", "AND",
            "ANY", "ARE", "AS", "ASC", "ASSERTION", "AT", "AUTHORIZATION", "AVG", "BEGIN", "BETWEEN", "BIT",
            "BIT_LENGTH", "BOTH", "BY", "CASCADE", "CASCADED", "CASE", "CAST", "CATALOG", "CHAR", "CHARACTER",
            "CHAR_LENGTH", "CHARACTER_LENGTH", "CHECK", "CLOSE", "COALESCE", "COLLATE", "COLLATION", "COLUMN",
            "COMMIT", "CONNECT", "CONNECTION", "CONSTRAINT", "CONSTRAINTS", "CONTINUE", "CONVERT", "CORRESPONDING",
            "COUNT", "CREATE", "CROSS", "CURRENT", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP",
            "CURRENT_USER", "CURSOR", "DATE", "DAY", "DEALLOCATE", "DEC", "DECIMAL", "DECLARE", "DEFAULT",
            "DEFERRABLE", "DEFERRED", "DELETE", "DESC", "DESCRIBE", "DESCRIPTOR", "DIAGNOSTICS", "DISCONNECT",
            "DISTINCT", "DOMAIN", "DOUBLE", "DROP", "ELSE", "END", "ESCAPE", "EXCEPT", "EXCEPTION", "EXEC",
            "EXECUTE", "EXISTS", "EXTERNAL", "EXTRACT", "FALSE", "FETCH", "FIRST", "FLOAT", "FOR", "FOREIGN",
            "FOUND", "FROM", "FULL", "GET", "GLOBAL", "GO", "GOTO", "GRANT", "GROUP", "HAVING", "HOUR", "IDENTITY",
            "IMMEDIATE", "IN", "INDICATOR", "INITIALLY", "INNER", "INPUT", "INSENSITIVE", "INSERT", "INT", "INTEGER",
            "INTERSECT", "INTERVAL", "INTO", "IS", "ISOLATION", "JOIN", "KEY", "LANGUAGE", "LAST", "LEADING", "LEFT",
            "LEVEL", "LIKE", "LOCAL", "LOWER", "MATCH", "MAX", "MIN", "MINUTE", "MODULE", "MONTH", "NAMES",
            "NATIONAL", "NATURAL", "NCHAR", "NEXT", "NO", "NOT", "NULL", "NULLIF", "NUMERIC", "OCTET_LENGTH", "OF",
            "ON", "ONLY", "OPEN", "OPTION", "OR", "ORDER", "OUTER", "OUTPUT", "OVERLAPS", "PAD", "PARTIAL",
            "POSITION", "PRECISION", "PREPARE", "PRESERVE", "PRIMARY", "PRIOR", "PRIVILEGES", "PROCEDURE", "PUBLIC",
            "READ", "REAL", "REFERENCES", "RELATIVE", "RESTRICT", "REVOKE", "RIGHT", "ROLLBACK", "ROWS", "SCHEMA",
            "SCROLL", "SECOND", "SECTION", "SELECT", "SESSION", "SESSION_USER", "SET", "SIZE", "SMALLINT", "SOME",
            "SPACE", "SQL", "SQLCODE", "SQLERROR", "SQLSTATE", "SUBSTRING", "SUM", "SYSTEM_USER", "TABLE",
            "TEMPORARY", "THEN", "TIME", "TIMESTAMP", "TIMEZONE_HOUR", "TIMEZONE_MINUTE", "TO", "TRAILING",
            "TRANSACTION", "TRANSLATE", "TRANSLATION", "TRIM", "TRUE", "UNION", "UNIQUE", "UNKNOWN", "UPDATE",
            "UPPER", "USAGE", "USER", "USING", "VALUE", "VALUES", "VARCHAR", "VARYING", "VIEW", "WHEN", "WHENEVER",
            "WHERE", "WITH", "WORK", "WRITE", "YEAR", "ZONE", "LIMIT", "OFFSET", "NULLS", "RECURSIVE", "RETURNING",
            "ILIKE", "OVER", "PARTITION", "LATERAL", "FILTER");
    /** The functions a query of every database calls, each followed by {@code (}. */
    static final List<String> FUNCTIONS = List.of("COUNT", "SUM", "AVG", "MIN", "MAX", "UPPER", "LOWER", "LENGTH",
            "TRIM", "COALESCE", "NULLIF", "CAST", "ABS", "ROUND", "SUBSTRING", "CONCAT", "REPLACE", "EXTRACT", "NOW");
    /** The words that start a clause (SQL spec §3). */
    static final List<String> CLAUSES = List.of("SELECT", "FROM", "JOIN", "ON", "WHERE", "GROUP BY", "HAVING",
            "ORDER BY", "LIMIT", "OFFSET", "SET", "VALUES", "UPDATE", "DELETE FROM", "INSERT INTO");
    /** The words after which a table is named. */
    static final List<String> TARGET_AFTER = List.of("FROM", "JOIN", "UPDATE", "INTO");

    private static final System.Logger LOG = System.getLogger(SqlLanguage.class.getName());

    private SqlLanguage() {}

    /**
     * The language's JSON, {@code {"mode": "query", "dialect": {…}, "targets": {…}}}, within the
     * {@value PanelLanguage#MAX_JSON} characters of a panel language: past them, written again without the
     * {@code detail}s, then without the {@code type}s and {@code format}s too (every table and column stays), saying
     * so; past them still, refused.
     *
     * @param label    the pool's label, which a log names
     * @param metadata what the boot read of its tables
     * @throws IllegalStateException when even the leanest language is past the limit, naming its size
     */
    static String json(String label, PoolMetadata metadata) {
        return json(label, metadata, PanelLanguage.MAX_JSON);
    }

    /** {@link #json(String, PoolMetadata)} within {@code limit} characters. */
    static String json(String label, PoolMetadata metadata, int limit) {
        Map<String, Object> dialect = dialect(metadata);
        Map<String, Object> targets = targets(metadata);
        String json = write(dialect, targets);
        if (json.length() <= limit) {
            return json;
        }
        int full = json.length();
        for (List<String> dropped : List.of(List.of("detail"), List.of("type", "format"))) {
            drop(targets, dropped);
            json = write(dialect, targets);
            if (json.length() <= limit) {
                LOG.log(System.Logger.Level.INFO, "Mansart pools: the SQL language of pool '" + label + "', "
                        + targets.size() + " tables, is " + full + " characters, past the " + limit
                        + " a panel language may hold: written without " + (dropped.size() == 1 ? "details"
                        : "details or types"));
                return json;
            }
        }
        throw new IllegalStateException("the SQL language of " + targets.size() + " tables is " + json.length()
                + " characters even without details or types, past the " + limit + " a panel language may hold");
    }

    private static String write(Map<String, Object> dialect, Map<String, Object> targets) {
        Map<String, Object> language = new LinkedHashMap<>();
        language.put("mode", "query");
        language.put("dialect", dialect);
        language.put("targets", targets);
        return Json.write(language);
    }

    /** SQL's dialect for this database: its keywords, its quote, how it stores a name; no self. */
    private static Map<String, Object> dialect(PoolMetadata metadata) {
        Set<String> keywords = new LinkedHashSet<>(KEYWORDS);
        keywords.addAll(metadata.keywords());
        Map<String, Object> dialect = new LinkedHashMap<>();
        dialect.put("keywords", List.copyOf(keywords));
        dialect.put("functions", FUNCTIONS);
        dialect.put("clauses", CLAUSES);
        dialect.put("targetAfter", TARGET_AFTER);
        dialect.put("aliases", true);
        dialect.put("self", null);
        dialect.put("quote", "'");
        dialect.put("identifierQuote", String.valueOf(metadata.identifierQuote()));
        if (metadata.unquotedCase() != null) {
            dialect.put("unquotedCase", metadata.unquotedCase());
        }
        return dialect;
    }

    private static Map<String, Object> targets(PoolMetadata metadata) {
        Map<String, Object> targets = new LinkedHashMap<>();
        for (Table table : metadata.tables()) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            for (Column column : table.columns()) {
                Map<String, Object> attribute = new LinkedHashMap<>();
                type(column.jdbcType(), attribute);
                attribute.put("detail", column.sqlType() + " · column");
                if (column.target() != null) {
                    attribute.put("target", column.target());
                }
                attributes.put(column.name(), attribute);
            }
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("detail", (table.view() ? "view" : "table")
                    + (table.schema() == null ? "" : " · " + table.schema()));
            String shown = metadata.shown(table);
            if (!shown.equals(table.name())) {
                target.put("schema", table.schema());
            }
            target.put("attributes", attributes);
            targets.put(shown, target);
        }
        return targets;
    }

    /** Puts the JSON Schema {@code type}, and {@code format} for a date or a time, of a {@link Types} code. */
    private static void type(int jdbcType, Map<String, Object> attribute) {
        switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> attribute.put("type", "integer");
            case Types.REAL, Types.FLOAT, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> attribute.put("type", "number");
            case Types.BIT, Types.BOOLEAN -> attribute.put("type", "boolean");
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB -> attribute.put("type", "string");
            case Types.DATE -> format(attribute, "date");
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> format(attribute, "time");
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> format(attribute, "date-time");
            default -> {
                // a type JSON Schema has no word for: the column keeps its detail only
            }
        }
    }

    private static void format(Map<String, Object> attribute, String format) {
        attribute.put("type", "string");
        attribute.put("format", format);
    }

    /** Removes {@code keys} from every target and every attribute of {@code targets}. */
    private static void drop(Map<String, Object> targets, List<String> keys) {
        for (Object target : targets.values()) {
            Map<?, ?> entry = (Map<?, ?>) target;
            entry.keySet().removeAll(keys);
            for (Object attribute : ((Map<?, ?>) entry.get("attributes")).values()) {
                ((Map<?, ?>) attribute).keySet().removeAll(keys);
            }
        }
    }
}
