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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the pools panel reads of a database through {@link DatabaseMetaData} (SQL spec §4): the tables and views of its
 * catalog's user schemas, the system ones left out, in schema then name order, with their columns; how it quotes and
 * stores a name; the words its product adds to SQL's.
 *
 * @param catalog         the connection's catalog, or {@code null}
 * @param schema          the connection's current schema, whose tables are named bare, or {@code null}
 * @param identifierQuote the quote around an identifier, {@code "} when the database says none
 * @param unquotedCase    how it stores a name written without quotes, {@code lower}, {@code upper}, or {@code null}
 *                        when it keeps it as written
 * @param keywords        the words of {@link DatabaseMetaData#getSQLKeywords()}, in capitals
 * @param reserved        the words that cannot be a name unquoted, in capitals, when the database says which (on
 *                        PostgreSQL, the {@code R} and {@code T} words of {@code pg_get_keywords()}), {@code null}
 *                        when it does not: every keyword is then taken for one
 * @param tables          the tables and views
 */
record PoolMetadata(String catalog, String schema, char identifierQuote, String unquotedCase, List<String> keywords,
                    List<Table> tables, List<String> reserved) {

    /** The schemas of a database's own catalog, in lower case: never listed. */
    /** How long the boot spends on the foreign keys of all the tables. */
    static final Duration REFERENCES_BUDGET = Duration.ofSeconds(10);

    private static final System.Logger LOG = System.getLogger(PoolMetadata.class.getName());

    private static final Set<String> SYSTEM_SCHEMAS = Set.of("information_schema", "pg_catalog", "pg_toast", "sys",
            "mysql", "performance_schema");

    /**
     * A table or a view.
     *
     * @param schema  its schema, or {@code null}
     * @param name    its name, as the database stores it
     * @param kind    its type, as the database says it: {@code TABLE}, {@code BASE TABLE}, {@code VIEW}…
     * @param columns its columns, in order
     */
    record Table(String schema, String name, String kind, List<Column> columns) {

        /** Whether it is a view, which a language's detail says. */
        boolean view() {
            return kind.toUpperCase(Locale.ROOT).contains("VIEW");
        }
    }

    /**
     * A column of a table.
     *
     * @param name     its name, as the database stores it
     * @param jdbcType its {@link Types} code
     * @param sqlType  its SQL type with its size, such as {@code VARCHAR(200)} or {@code NUMERIC(10,2)}
     * @param target   for a column whose single-column foreign key refers to a table of the list, that table's
     *                 {@link #shown} name; {@code null} otherwise
     */
    record Column(String name, int jdbcType, String sqlType, String target) {}

    /** The name of {@code table} for the panel: bare in the current schema, {@code schema.name} in another. */
    String shown(Table table) {
        return table.schema() == null || table.schema().equals(schema) ? table.name()
                : table.schema() + "." + table.name();
    }

    /** {@code table} as a statement names it: its schema and its name each between the identifier quotes. */
    String quoted(Table table) {
        return (table.schema() == null ? "" : quote(table.schema()) + ".") + quote(table.name());
    }

    private String quote(String name) {
        String q = String.valueOf(identifierQuote);
        return q + name.replace(q, q + q) + q;
    }

    /**
     * Reads the metadata of {@code connection}'s database.
     *
     * @param references whether to read each table's foreign keys too, one more query per table: the boot does,
     *                   for its language; Tables does not
     */
    static PoolMetadata read(Connection connection, boolean references) throws SQLException {
        return read(connection, references, REFERENCES_BUDGET);
    }

    /**
     * The boot's read: on a read-only connection, given back as it was, with the foreign keys of every table within
     * {@link #REFERENCES_BUDGET}.
     */
    static PoolMetadata readOnly(Connection connection) throws SQLException {
        boolean readOnly = connection.isReadOnly();
        try {
            connection.setReadOnly(true);
        } catch (SQLException | RuntimeException _) {
            // a driver that refuses it is read as it is
        }
        try {
            return read(connection, true);
        } finally {
            try {
                connection.setReadOnly(readOnly);
            } catch (SQLException | RuntimeException _) {
                // the connection goes back to the pool all the same, which validates it
            }
        }
    }

    /**
     * {@link #read(Connection, boolean)} with the foreign keys read for at most {@code budget}: the tables that none
     * was read for have no {@code target}, and a WARNING says so.
     */
    static PoolMetadata read(Connection connection, boolean references, Duration budget) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String catalog = connection.getCatalog();
        String schema = currentSchema(connection);
        String quote = meta.getIdentifierQuoteString();
        char identifierQuote = quote == null || quote.isBlank() ? '"' : quote.charAt(0);
        String unquotedCase = null;
        if (meta.storesLowerCaseIdentifiers()) {
            unquotedCase = "lower";
        } else if (meta.storesUpperCaseIdentifiers()) {
            unquotedCase = "upper";
        }
        List<String> keywords = meta.getSQLKeywords() == null ? List.of()
                : Arrays.stream(meta.getSQLKeywords().split(",")).map(String::strip).filter(w -> !w.isEmpty())
                .map(w -> w.toUpperCase(Locale.ROOT)).distinct().toList();
        Map<String, Table> tables = new LinkedHashMap<>();
        try (ResultSet found = meta.getTables(catalog, null, "%", null)) {
            while (found.next()) {
                String tableSchema = found.getString("TABLE_SCHEM");
                String kind = Objects.requireNonNullElse(found.getString("TABLE_TYPE"), "");
                if (listed(tableSchema, kind)) {
                    String name = found.getString("TABLE_NAME");
                    tables.put(key(tableSchema, name), new Table(tableSchema, name, kind, new ArrayList<>()));
                }
            }
        }
        for (String each : tables.values().stream().map(Table::schema).distinct().toList()) {
            try (ResultSet found = meta.getColumns(catalog, each, "%", "%")) {
                while (found.next()) {
                    Table table = tables.get(key(found.getString("TABLE_SCHEM"), found.getString("TABLE_NAME")));
                    if (table != null) {
                        table.columns().add(new Column(found.getString("COLUMN_NAME"), found.getInt("DATA_TYPE"),
                                sqlType(found.getString("TYPE_NAME"), found.getInt("DATA_TYPE"),
                                        found.getInt("COLUMN_SIZE"), found.getInt("DECIMAL_DIGITS")), null));
                    }
                }
            }
        }
        PoolMetadata read = new PoolMetadata(catalog, schema, identifierQuote, unquotedCase, keywords,
                tables.values().stream().sorted(Comparator.comparing((Table t) -> Objects.requireNonNullElse(
                        t.schema(), "")).thenComparing(Table::name)).toList(), reserved(connection, meta));
        return references ? read.withReferences(meta, budget) : read;
    }

    /**
     * The words PostgreSQL does not let a name be unquoted: the {@code R} (reserved) and {@code T} (reserved, but
     * allowed as a function or a type) words of {@code pg_get_keywords()}, which {@link DatabaseMetaData#getSQLKeywords()}
     * mixes with every unreserved one ({@code comments}, {@code owner}, {@code text}…). {@code null} on any other
     * database, and when PostgreSQL does not answer.
     */
    private static List<String> reserved(Connection connection, DatabaseMetaData meta) {
        try {
            if (!"PostgreSQL".equals(meta.getDatabaseProductName())) {
                return null;
            }
            try (Statement statement = connection.createStatement();
                 ResultSet words = statement.executeQuery("SELECT word, catcode FROM pg_get_keywords()")) {
                return reservedWords(words);
            }
        } catch (SQLException | RuntimeException _) {
            return null;
        }
    }

    /** The {@code word}s, in capitals, whose {@code catcode} is {@code R} or {@code T}, in the order read. */
    static List<String> reservedWords(ResultSet words) throws SQLException {
        List<String> reserved = new ArrayList<>();
        while (words.next()) {
            String category = words.getString("catcode");
            if ("R".equals(category) || "T".equals(category)) {
                reserved.add(words.getString("word").toUpperCase(Locale.ROOT));
            }
        }
        return List.copyOf(reserved);
    }

    /** This metadata, each column of a single-column foreign key to a listed table given its {@code target}. */
    private PoolMetadata withReferences(DatabaseMetaData meta, Duration budget) throws SQLException {
        Map<String, Table> byKey = new LinkedHashMap<>();
        tables.forEach(table -> byKey.put(key(table.schema(), table.name()), table));
        Map<String, Map<String, List<String[]>>> keys = importedKeys(meta, budget);
        List<Table> out = new ArrayList<>(tables.size());
        for (Table table : tables) {
            Map<String, String> targets = new LinkedHashMap<>();
            for (List<String[]> columns : keys.getOrDefault(key(table.schema(), table.name()), Map.of()).values()) {
                Table referenced = columns.size() == 1 ? byKey.get(columns.getFirst()[1]) : null;
                if (referenced != null) {
                    targets.put(columns.getFirst()[0], shown(referenced));
                }
            }
            out.add(new Table(table.schema(), table.name(), table.kind(), table.columns().stream()
                    .map(c -> new Column(c.name(), c.jdbcType(), c.sqlType(), targets.get(c.name()))).toList()));
        }
        return new PoolMetadata(catalog, schema, identifierQuote, unquotedCase, keywords, List.copyOf(out),
                reserved);
    }

    /**
     * The foreign keys of every table, by table then by constraint: one call per schema where the driver accepts it,
     * one per table otherwise, for at most {@code budget}; a WARNING says when some tables were left.
     */
    private Map<String, Map<String, List<String[]>>> importedKeys(DatabaseMetaData meta, Duration budget)
            throws SQLException {
        Map<String, Map<String, List<String[]>>> keys = new LinkedHashMap<>();
        long deadline = System.nanoTime() + budget.toNanos();
        boolean complete = true;
        for (String each : tables.stream().map(Table::schema).distinct().toList()) {
            complete = System.nanoTime() - deadline < 0 && (importedKeysOfSchema(meta, each, keys)
                    || importedKeysOfTables(meta, each, keys, deadline));
            if (!complete) {
                break;
            }
        }
        if (!complete) {
            LOG.log(System.Logger.Level.WARNING, "Mansart pools: the foreign keys of some tables were not read within "
                    + budget.toSeconds() + " s: their columns have no reference in the SQL language");
        }
        return keys;
    }

    /** One call per table of {@code each}, a driver having refused the call with none; {@code false} past the deadline. */
    private boolean importedKeysOfTables(DatabaseMetaData meta, String each,
                                         Map<String, Map<String, List<String[]>>> keys, long deadline)
            throws SQLException {
        for (Table table : tables.stream().filter(t -> Objects.equals(t.schema(), each)).toList()) {
            if (System.nanoTime() - deadline >= 0) {
                return false;
            }
            try (ResultSet found = meta.getImportedKeys(catalog, table.schema(), table.name())) {
                collect(found, keys);
            }
        }
        return true;
    }

    /**
     * Reads the foreign keys of every table of {@code each} with one call, no table given, into {@code keys}; {@code
     * false}, and nothing added, when the driver refuses it.
     */
    private boolean importedKeysOfSchema(DatabaseMetaData meta, String each,
                                         Map<String, Map<String, List<String[]>>> keys) {
        Map<String, Map<String, List<String[]>>> read = new LinkedHashMap<>();
        try (ResultSet found = meta.getImportedKeys(catalog, each, null)) {
            collect(found, read);
        } catch (SQLException | RuntimeException _) {
            return false;
        }
        keys.putAll(read);
        return true;
    }

    /** Adds the foreign keys of {@code found} to {@code keys}: by table, then by constraint, its columns in order. */
    private static void collect(ResultSet found, Map<String, Map<String, List<String[]>>> keys) throws SQLException {
        while (found.next()) {
            String constraint = Objects.requireNonNullElse(found.getString("FK_NAME"),
                    found.getString("FKCOLUMN_NAME"));
            keys.computeIfAbsent(key(found.getString("FKTABLE_SCHEM"), found.getString("FKTABLE_NAME")),
                    k -> new LinkedHashMap<>()).computeIfAbsent(constraint, k -> new ArrayList<>()).add(new String[] {
                found.getString("FKCOLUMN_NAME"), key(found.getString("PKTABLE_SCHEM"),
                        found.getString("PKTABLE_NAME"))});
        }
    }

    /**
     * A SQL type with its size: {@code VARCHAR(200)}, {@code NUMERIC(10,2)}; as the database names it when it says a
     * size already, has none, or one past what a column is declared with ({@code text}, an unbounded
     * {@code CHARACTER VARYING}).
     */
    static String sqlType(String typeName, int jdbcType, int size, int digits) {
        String name = Objects.requireNonNullElse(typeName, "");
        if (name.contains("(") || size <= 0) {
            return name;
        }
        String scale = digits > 0 ? "," + digits : "";
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR, Types.BINARY, Types.VARBINARY ->
                    size < 1_000_000 ? name + "(" + size + ")" : name;
            case Types.NUMERIC, Types.DECIMAL -> size < 1000 ? name + "(" + size + scale + ")" : name;
            default -> name;
        };
    }

    /** The current schema, or {@code null} when the driver does not say. */
    private static String currentSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (SQLException | AbstractMethodError _) {
            return null;
        }
    }

    /** Whether a table of {@code schema} and of type {@code kind} is listed: a table or a view of a user schema. */
    private static boolean listed(String schema, String kind) {
        String type = kind.toUpperCase(Locale.ROOT);
        boolean tableOrView = (type.contains("TABLE") || type.contains("VIEW")) && !type.contains("SYSTEM")
                && !type.contains("TEMPORARY") && !type.contains("INDEX");
        String lower = schema == null ? "" : schema.toLowerCase(Locale.ROOT);
        return tableOrView && !SYSTEM_SCHEMAS.contains(lower) && !lower.startsWith("pg_temp")
                && !lower.startsWith("pg_toast");
    }

    private static String key(String schema, String name) {
        return Objects.requireNonNullElse(schema, "") + "\u0000" + name;
    }
}
