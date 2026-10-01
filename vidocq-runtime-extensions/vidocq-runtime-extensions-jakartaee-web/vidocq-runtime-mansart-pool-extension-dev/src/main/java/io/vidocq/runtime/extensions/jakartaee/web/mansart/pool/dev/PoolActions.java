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

import io.vidocq.mansart.pool.PoolConfig;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev.PoolMetadata.Table;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.live.MansartPoolsLive;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Column;
import io.vidocq.runtime.spi.devconsole.PanelAction.Argument;
import io.vidocq.runtime.spi.devconsole.PanelSample;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The actions of one pool in the pools panel (SQL spec §4.1), in a tab named after the pool: <i>Tables</i>,
 * <i>Describe</i> and <i>Preview</i> of a table listed at boot, <i>Query</i>, which only reads, and <i>Execute</i>,
 * asked first and rolled back unless committed. Their SQL runs through the pool's {@link SqlRunner}; what the boot
 * read of its tables, {@link PoolMetadata}, gives the tables Describe and Preview offer, how Preview quotes one, and
 * the panel's {@code sql} language. A pool whose tables could not be read at boot has no Describe and no Preview, and
 * says why at WARNING, never with its URL.
 */
final class PoolActions {

    /** The label of the unnamed pool, the {@code @Default} one, whose key is {@code default}. */
    static final String DEFAULT_LABEL = "@Default";
    static final String TABLES = "tables";
    static final String DESCRIBE = "describe";
    static final String PREVIEW = "preview";
    static final String QUERY = "query";
    static final String EXECUTE = "execute";
    static final String STATEMENT = "statement";
    static final String TABLE = "table";
    static final String LIMIT = "limit";
    static final String TRANSACTION = "transaction";
    /** How many rows Preview shows when no limit is given. */
    static final int DEFAULT_LIMIT = 100;
    /** The longest key, so that {@code <key>.describe} stays within the 40 characters of an action id. */
    static final int MAX_KEY = 30;
    /** The columns of Tables: a table's schema, name, kind and number of columns, then the replays of it. */
    static final List<Column> TABLE_COLUMNS = List.of(new Column("schema", ""), new Column("name", ""),
            new Column("kind", ""), new Column("columns", ""), new Column(PanelSample.REPLAY_COLUMN, ""),
            new Column(PanelSample.REPLAY_COLUMN, ""));
    /** The columns of Describe. */
    static final List<Column> COLUMN_COLUMNS = List.of(new Column("name", ""), new Column("type", ""),
            new Column("nullable", ""), new Column("default", ""), new Column("primary key", ""),
            new Column("foreign key", ""));

    private static final String SQL_MEMBER = "sql";
    private static final String PARAMS_MEMBER = "params";
    private static final System.Logger LOG = System.getLogger(PoolActions.class.getName());

    private final String label;
    private final String key;
    private final SqlRunner runner;
    private final PoolMetadata metadata;

    /**
     * @param label    the pool's label, {@value #DEFAULT_LABEL} or its name: the title of its tab
     * @param key      its key, which its actions' ids and its language's id start with
     * @param runner   runs its SQL
     * @param metadata what the boot read of its tables, or {@code null} when it could not
     */
    PoolActions(String label, String key, SqlRunner runner, PoolMetadata metadata) {
        this.label = Objects.requireNonNull(label, "label");
        this.key = Objects.requireNonNull(key, "key");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.metadata = metadata;
    }

    /**
     * The actions of each pool of {@code pools}, in their order: {@value #DEFAULT_LABEL} first, then the named ones in
     * name order, as the runtime extension publishes them. Each pool's tables are read now, once per boot.
     */
    static List<PoolActions> of(List<MansartPoolsLive.Pool> pools) {
        Set<String> used = new HashSet<>();
        List<PoolActions> out = new ArrayList<>();
        for (MansartPoolsLive.Pool pool : pools) {
            PoolConfig config = pool.pool().config();
            SqlRunner reader = new SqlRunner(pool.pool(), '"', SqlRunner.TIMEOUT_SECONDS, config.jdbcUrl(),
                    config.username(), config.password());
            PoolMetadata metadata = null;
            try (Connection connection = pool.pool().getConnection()) {
                metadata = PoolMetadata.read(connection, true);
            } catch (SQLException | RuntimeException failed) {
                String why = failed instanceof SQLException ? reader.masked(String.valueOf(failed.getMessage()))
                        .replaceAll("\\s+", " ") : failed.getClass().getName();
                LOG.log(System.Logger.Level.WARNING, "Mansart pools: the tables of pool '" + pool.label()
                        + "' could not be read, it has no Describe, no Preview and no SQL language: " + why);
            }
            SqlRunner runner = metadata == null ? reader : new SqlRunner(pool.pool(), metadata.identifierQuote(),
                    SqlRunner.TIMEOUT_SECONDS, config.jdbcUrl(), config.username(), config.password());
            out.add(new PoolActions(pool.label(), key(pool.label(), used), runner, metadata));
        }
        return List.copyOf(out);
    }

    /**
     * The key of a pool: {@code default} for {@value #DEFAULT_LABEL}; its name in lower case otherwise, every
     * character but a letter, a digit or {@code -} made {@code -}, {@code p-} first when it starts with no letter, at
     * most {@value #MAX_KEY} characters, {@code -2}, {@code -3}… when {@code used} already holds it. Adds it to
     * {@code used}.
     */
    static String key(String label, Set<String> used) {
        String base = label.equals(DEFAULT_LABEL) ? "default"
                : label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        if (base.isEmpty() || base.charAt(0) < 'a' || base.charAt(0) > 'z') {
            base = "p-" + base;
        }
        base = base.substring(0, Math.min(base.length(), MAX_KEY));
        String key = base;
        for (int n = 2; !used.add(key); n++) {
            String suffix = "-" + n;
            key = base.substring(0, Math.min(base.length(), MAX_KEY - suffix.length())) + suffix;
        }
        return key;
    }

    String label() {
        return label;
    }

    String key() {
        return key;
    }

    /** What the boot read of the pool's tables, or {@code null} when it could not. */
    PoolMetadata metadata() {
        return metadata;
    }

    /** The id of the pool's language, which its SQL editors name: {@code sql-<key>}. */
    String languageId() {
        return "sql-" + key;
    }

    /** Tables, Describe and Preview when the boot listed a table, Query, Execute: the pool's tab. */
    List<PanelAction> actions() {
        String group = label.length() > PanelAction.MAX_GROUP ? label.substring(0, PanelAction.MAX_GROUP) : label;
        List<PanelAction> actions = new ArrayList<>();
        actions.add(new PanelAction(key + "." + TABLES, "Tables", null, List.of(), given -> tables(), group,
                "The tables and views of the pool's schemas, the system ones left out. Describe and Preview fill "
                        + "their form with one of them."));
        List<String> names = tableNames();
        if (!names.isEmpty()) {
            Argument table = new Argument(TABLE, "Table", names, null, null);
            actions.add(new PanelAction(key + "." + DESCRIBE, "Describe", null, List.of(table),
                    given -> describe(given.get(TABLE)), group, "The columns of a table listed at boot: its SQL "
                            + "type, nullable, its default, its place in the primary key, the column a foreign key "
                            + "refers to; its indexes in the summary."));
            actions.add(new PanelAction(key + "." + PREVIEW, "Preview", null, List.of(table,
                    Argument.matching(LIMIT, "Rows (1 to 1000, 100 when empty)", "|[1-9][0-9]{0,2}|1000")),
                    given -> preview(given.get(TABLE), given.get(LIMIT)), group,
                    "The first rows of a table, read in a transaction rolled back."));
        }
        Argument statement = Argument.json(STATEMENT, "Statement", schema());
        actions.add(new PanelAction(key + "." + QUERY, "Query", null, List.of(statement), given -> call(false, given),
                group, "A SQL query: SELECT, WITH, VALUES, SHOW, EXPLAIN or TABLE, run in a transaction always "
                        + "rolled back; at most " + SqlRunner.MAX_ROWS + " rows are shown.\nA named parameter, :name, "
                        + "takes the member name of params."));
        actions.add(new PanelAction(key + "." + EXECUTE, "Execute", confirmation(), List.of(statement,
                Argument.oneOf(TRANSACTION, "Transaction", SqlRunner.ROLLBACK, SqlRunner.COMMIT)),
                given -> call(true, given), group, "Any one SQL statement, run in a transaction rolled back unless "
                        + "commit is asked: the rows it answers, or how many rows it changed.\nA named parameter, "
                        + ":name, takes the member name of params."));
        return actions;
    }

    /** The question Execute asks before it runs. */
    String confirmation() {
        return "Runs this SQL on " + label + ". A DDL statement (CREATE, ALTER, DROP, TRUNCATE…) may be committed by "
                + "the database itself whatever is chosen.";
    }

    /**
     * The JSON Schema of the {@value #STATEMENT} argument: {@code sql}, which the page shows in its query editor with
     * the pool's language, and {@code params}, in its JSON editor whose schema follows the query.
     */
    String schema() {
        return Json.write(ordered("type", "object", "properties", ordered(
                SQL_MEMBER, ordered("type", "string", "format", "textarea", "contentMediaType", "text/x-query",
                        "x-language", languageId(), "description", "SELECT … FROM … WHERE … = :name"),
                PARAMS_MEMBER, ordered("type", "string", "format", "textarea", "x-parameters-of", SQL_MEMBER,
                        "description", "the named parameters, a JSON object: {\"name\": 3}")),
                "required", List.of(SQL_MEMBER)));
    }

    /** The names Describe and Preview offer: the boot's tables, bare in the current schema; none without them. */
    List<String> tableNames() {
        if (metadata == null) {
            return List.of();
        }
        return metadata.tables().stream().map(metadata::shown)
                .filter(name -> !name.isBlank() && name.length() <= PanelAction.MAX_VALUE_LENGTH).distinct().toList();
    }

    private ActionResult tables() {
        Set<String> known = Set.copyOf(tableNames());
        return runner.read(connection -> {
            PoolMetadata now = PoolMetadata.read(connection, false);
            List<List<Object>> rows = new ArrayList<>();
            int views = 0;
            for (Table table : now.tables()) {
                String shown = now.shown(table);
                boolean replay = known.contains(shown);
                rows.add(Arrays.asList(table.schema(), table.name(), table.kind(), table.columns().size(),
                        replay ? replay(DESCRIBE, shown) : "", replay ? replay(PREVIEW, shown) : ""));
                views += table.view() ? 1 : 0;
            }
            int tables = rows.size() - views;
            return ActionResult.rows(plural(tables, "table") + (views == 0 ? "" : ", " + plural(views, "view")),
                    TABLE_COLUMNS, rows, false);
        });
    }

    /** {@code <key>.<action> {"table": "<name>"}}: a replay that fills that action's form with the table. */
    private String replay(String action, String table) {
        return key + "." + action + " " + Json.write(Map.of(TABLE, table));
    }

    private ActionResult describe(String shown) {
        Table table = table(shown);
        if (table == null) {
            return refused("unknown table " + shown);
        }
        return runner.read(connection -> {
            DatabaseMetaData meta = connection.getMetaData();
            Map<String, Short> primary = new LinkedHashMap<>();
            try (ResultSet found = meta.getPrimaryKeys(metadata.catalog(), table.schema(), table.name())) {
                while (found.next()) {
                    primary.put(found.getString("COLUMN_NAME"), found.getShort("KEY_SEQ"));
                }
            }
            Map<String, String> foreign = new LinkedHashMap<>();
            try (ResultSet found = meta.getImportedKeys(metadata.catalog(), table.schema(), table.name())) {
                while (found.next()) {
                    foreign.put(found.getString("FKCOLUMN_NAME"), found.getString("PKTABLE_NAME") + "."
                            + found.getString("PKCOLUMN_NAME"));
                }
            }
            List<List<Object>> rows = new ArrayList<>();
            String escape = meta.getSearchStringEscape();
            try (ResultSet found = meta.getColumns(metadata.catalog(), pattern(table.schema(), escape),
                    pattern(table.name(), escape), "%")) {
                while (found.next()) {
                    if (!Objects.equals(found.getString("TABLE_SCHEM"), table.schema())
                            || !table.name().equals(found.getString("TABLE_NAME"))) {
                        continue;
                    }
                    String name = found.getString("COLUMN_NAME");
                    String nullable = found.getString("IS_NULLABLE");
                    rows.add(Arrays.asList(name, PoolMetadata.sqlType(found.getString("TYPE_NAME"),
                                    found.getInt("DATA_TYPE"), found.getInt("COLUMN_SIZE"),
                                    found.getInt("DECIMAL_DIGITS")),
                            "YES".equals(nullable) ? Boolean.TRUE : "NO".equals(nullable) ? Boolean.FALSE : null,
                            found.getString("COLUMN_DEF"), primary.get(name), foreign.get(name)));
                }
            }
            List<String> indexes = indexes(meta, table);
            return ActionResult.rows(plural(rows.size(), "column") + " · " + (indexes.isEmpty() ? "no index"
                    : "indexes: " + String.join(", ", indexes)), COLUMN_COLUMNS, rows, false);
        });
    }

    /** The indexes of {@code table}, each {@code name (column, column)}, its columns in their order. */
    private List<String> indexes(DatabaseMetaData meta, Table table) throws SQLException {
        Map<String, TreeMap<Short, String>> byName = new LinkedHashMap<>();
        try (ResultSet found = meta.getIndexInfo(metadata.catalog(), table.schema(), table.name(), false, true)) {
            while (found.next()) {
                String index = found.getString("INDEX_NAME");
                if (index != null && found.getShort("TYPE") != DatabaseMetaData.tableIndexStatistic) {
                    byName.computeIfAbsent(index, name -> new TreeMap<>()).put(found.getShort("ORDINAL_POSITION"),
                            Objects.requireNonNullElse(found.getString("COLUMN_NAME"), "?"));
                }
            }
        }
        return byName.entrySet().stream()
                .map(index -> index.getKey() + " (" + String.join(", ", index.getValue().values()) + ")").toList();
    }

    private ActionResult preview(String shown, String limit) {
        Table table = table(shown);
        if (table == null) {
            return refused("unknown table " + shown);
        }
        int rows = limit == null || limit.isEmpty() ? DEFAULT_LIMIT : Integer.parseInt(limit);
        return runner.preview("SELECT * FROM " + metadata.quoted(table), rows);
    }

    /** The table of the boot's list whose shown name is {@code shown}, or {@code null}. */
    private Table table(String shown) {
        if (metadata == null) {
            return null;
        }
        return metadata.tables().stream().filter(t -> metadata.shown(t).equals(shown)).findFirst().orElse(null);
    }

    /** Runs the statement of a call: Query, or Execute with the transaction asked. */
    private ActionResult call(boolean write, Map<String, String> given) {
        Object parsed;
        try {
            parsed = Json.parse(given.getOrDefault(STATEMENT, "{}"));
        } catch (IllegalArgumentException unreadable) {
            return refused(STATEMENT + ": " + unreadable.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> members)) {
            return refused(STATEMENT + ": not a JSON object");
        }
        for (Object name : members.keySet()) {
            if (!SQL_MEMBER.equals(name) && !PARAMS_MEMBER.equals(name)) {
                return refused(name + ": unknown argument");
            }
        }
        if (!(members.get(SQL_MEMBER) instanceof String sql) || sql.isBlank()) {
            return refused(SQL_MEMBER + ": missing");
        }
        Map<String, Object> params;
        try {
            params = params(members.get(PARAMS_MEMBER));
        } catch (IllegalArgumentException refused) {
            return refused(refused.getMessage());
        }
        return write ? runner.execute(sql.strip(), params, SqlRunner.COMMIT.equals(given.get(TRANSACTION)))
                : runner.query(sql.strip(), params);
    }

    /** {@code params}: absent, blank, a JSON object or the text of one, each member a value or a list of values. */
    static Map<String, Object> params(Object given) {
        Object value = given;
        if (value instanceof String text) {
            if (text.isBlank()) {
                return Map.of();
            }
            try {
                value = Json.parse(text);
            } catch (IllegalArgumentException unreadable) {
                throw new IllegalArgumentException(PARAMS_MEMBER + ": " + unreadable.getMessage());
            }
        }
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> members)) {
            throw new IllegalArgumentException(PARAMS_MEMBER + ": not a JSON object");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> member : members.entrySet()) {
            Object parameter = member.getValue();
            if (parameter instanceof Map<?, ?> || parameter instanceof List<?> list
                    && list.stream().anyMatch(element -> element instanceof Map<?, ?> || element instanceof List<?>)) {
                throw new IllegalArgumentException(PARAMS_MEMBER + "." + member.getKey()
                        + ": not a value or a list of values");
            }
            out.put(String.valueOf(member.getKey()), parameter);
        }
        return out;
    }

    private static ActionResult refused(String why) {
        return new ActionResult(why, null, null, true, null);
    }

    /** {@code name} as a metadata pattern matches it alone: its {@code _} and {@code %} escaped. */
    private static String pattern(String name, String escape) {
        if (name == null || escape == null || escape.isEmpty()) {
            return name;
        }
        return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    /** A JSON object of these names and values, in this order. */
    private static Map<String, Object> ordered(Object... namesAndValues) {
        Map<String, Object> object = new LinkedHashMap<>();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            object.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return object;
    }
}
