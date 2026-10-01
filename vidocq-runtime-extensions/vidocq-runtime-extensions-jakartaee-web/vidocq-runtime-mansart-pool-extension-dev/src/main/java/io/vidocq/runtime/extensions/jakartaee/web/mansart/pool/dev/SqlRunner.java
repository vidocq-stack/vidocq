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

import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Column;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Runs SQL on one pool for the pools panel (SQL spec §4.2). A call takes a connection from the pool, turns its
 * autoCommit off, runs one statement, commits only when asked and rolls back otherwise, always when the call throws,
 * then gives the connection back with its autoCommit and its read-only flag as they were. What only reads runs on a
 * read-only connection too. A statement is cancelled past its timeout. A named parameter {@code :name} is bound from
 * {@code params}: a string, a number, a boolean or {@code null} with {@code setObject}, a list as an array where the
 * driver makes one. A refusal comes before anything runs. An {@link SQLException} is an error result whose line is
 * the database's message, the pool's URL, password and user masked, and whose details give its SQLState and vendor
 * code.
 */
final class SqlRunner {

    /** How many rows a query shows; one more is read, to say that there are more. */
    static final int MAX_ROWS = 100;
    /** How long a statement may run, in seconds, before it is cancelled. */
    static final int TIMEOUT_SECONDS = 30;
    /** The transaction Execute takes by default: rolled back. */
    static final String ROLLBACK = "rollback";
    /** The transaction Execute takes when asked: committed. */
    static final String COMMIT = "commit";
    /** The longest name or type of a column a result shows. */
    private static final int MAX_NAME = ActionResult.Column.MAX_NAME;
    /** The SQLState of a statement cancelled, as PostgreSQL and H2 say it. */
    private static final String CANCELLED = "57014";

    private final DataSource pool;
    private final char identifierQuote;
    private final int timeoutSeconds;
    private final String url;
    private final String user;
    private final String password;

    /**
     * @param pool            the pool the calls take a connection from
     * @param identifierQuote the database's identifier quote, which {@link SqlText} reads
     * @param timeoutSeconds  how long a statement may run, {@value #TIMEOUT_SECONDS} outside tests
     * @param url             the pool's JDBC URL, masked in a message; never shown
     * @param user            its user, masked in a message after the word user; may be {@code null}
     * @param password        its password, masked in a message when it is four characters or more; may be
     *                        {@code null}
     */
    SqlRunner(DataSource pool, char identifierQuote, int timeoutSeconds, String url, String user, String password) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.identifierQuote = identifierQuote;
        this.timeoutSeconds = timeoutSeconds;
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /**
     * Runs a statement that only reads, whose first word is one of {@link SqlText#READS}, in a transaction always
     * rolled back: at most {@value #MAX_ROWS} rows, the next one setting {@code more}.
     */
    ActionResult query(String sql, Map<String, Object> params) {
        return run(sql, params, true, false, MAX_ROWS);
    }

    /**
     * Runs any one statement in a transaction rolled back unless {@code commit}: the rows it answers, as
     * {@link #query} shows them, or the number of rows it changed.
     */
    ActionResult execute(String sql, Map<String, Object> params, boolean commit) {
        return run(sql, params, false, commit, MAX_ROWS);
    }

    /** Runs {@code sql}, a {@code SELECT} the panel built, as a query: its first {@code limit} rows. */
    ActionResult preview(String sql, int limit) {
        return run(sql, Map.of(), true, false, limit);
    }

    /** What the panel reads of a connection, such as its tables' metadata. */
    @FunctionalInterface
    interface Reading {
        ActionResult read(Connection connection) throws SQLException;
    }

    /**
     * What {@code reading} reads on a connection of the pool, read-only, given back to the pool as it was; an error
     * result, masked as a statement's is, when it throws an {@link SQLException}.
     */
    ActionResult read(Reading reading) {
        try (Connection connection = pool.getConnection()) {
            boolean readOnly = connection.isReadOnly();
            readOnly(connection, true);
            try {
                return reading.read(connection);
            } finally {
                readOnly(connection, readOnly);
            }
        } catch (SQLException failed) {
            return failure(failed, new LinkedHashMap<>());
        }
    }

    /** What a call answers: the rows, or the number of rows changed; {@code columns} is {@code null} for a count. */
    private record Answer(List<Column> columns, List<List<Object>> rows, boolean more, int count) {}

    private ActionResult run(String sql, Map<String, Object> params, boolean reads, boolean commit, int limit) {
        long start = System.nanoTime();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sql", sql);
        details.put("params", params);
        details.put("transaction", reads ? "read-only" : commit ? COMMIT : ROLLBACK);
        String refusal = sql.isBlank() ? "sql: missing"
                : SqlText.severalStatements(sql, identifierQuote) ? "one statement at a time"
                : reads && !SqlText.READS.contains(SqlText.firstWord(sql, identifierQuote))
                ? "Query only reads: use Execute" : null;
        SqlText.Named named = SqlText.named(sql, identifierQuote);
        for (String name : named.names()) {
            if (refusal == null && !params.containsKey(name)) {
                refusal = "missing parameter " + name;
            }
        }
        if (refusal != null) {
            return new ActionResult(refusal, null, null, true, Json.write(details));
        }
        try (Connection connection = pool.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            boolean readOnly = connection.isReadOnly();
            try {
                if (reads) {
                    readOnly(connection, true);
                }
                connection.setAutoCommit(false);
                Answer answer = statement(connection, named, params, limit);
                if (commit) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
                return result(answer, reads ? null : commit ? "committed" : "rolled back", start, details);
            } catch (SQLException | RuntimeException failed) {
                quietly(connection::rollback);
                throw failed;
            } finally {
                quietly(() -> connection.setAutoCommit(autoCommit));
                if (reads) {
                    readOnly(connection, readOnly);
                }
            }
        } catch (SQLException failed) {
            return failure(failed, details);
        }
    }

    private Answer statement(Connection connection, SqlText.Named named, Map<String, Object> params, int limit)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(named.sql())) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(limit + 1);
            bind(connection, statement, named.names(), params);
            if (!statement.execute()) {
                return new Answer(null, List.of(), false, statement.getUpdateCount());
            }
            try (ResultSet results = statement.getResultSet()) {
                return rows(results, limit);
            }
        }
    }

    /** The first {@code limit} rows of {@code results}, and whether it has more. */
    private static Answer rows(ResultSet results, int limit) throws SQLException {
        ResultSetMetaData meta = results.getMetaData();
        int width = meta.getColumnCount();
        List<Column> columns = new ArrayList<>(width);
        for (int i = 1; i <= width; i++) {
            columns.add(new Column(cut(meta.getColumnLabel(i)), cut(meta.getColumnTypeName(i))));
        }
        List<List<Object>> rows = new ArrayList<>();
        while (results.next()) {
            if (rows.size() == limit) {
                return new Answer(columns, rows, true, -1);
            }
            List<Object> row = new ArrayList<>(width);
            for (int i = 1; i <= width; i++) {
                row.add(SqlValues.of(results, i, meta.getColumnType(i), meta.getColumnTypeName(i)));
            }
            rows.add(row);
        }
        return new Answer(columns, rows, false, -1);
    }

    private static ActionResult result(Answer answer, String state, long start, Map<String, Object> details) {
        String after = state == null ? "" : " · " + state;
        if (answer.columns() == null) {
            return new ActionResult(count(answer.count(), false) + after, null, null, false, Json.write(details));
        }
        ActionResult rows = ActionResult.rows(count(answer.rows().size(), answer.more()) + " in "
                + (System.nanoTime() - start) / 1_000_000 + " ms" + after, answer.columns(), answer.rows(),
                answer.more());
        return new ActionResult(rows.summary(), rows.contentType(), rows.body(), false, Json.write(details));
    }

    /** {@code no row}, {@code 1 row}, {@code N rows}, or {@code first N rows} when there are more. */
    static String count(long n, boolean more) {
        return more ? "first " + n + " rows" : n == 0 ? "no row" : n == 1 ? "1 row" : n + " rows";
    }

    /** Binds each {@code ?} of the statement to the parameter of that name. */
    private static void bind(Connection connection, PreparedStatement statement, List<String> names,
                             Map<String, Object> params) throws SQLException {
        for (int i = 0; i < names.size(); i++) {
            Object value = params.get(names.get(i));
            if (value instanceof List<?> list) {
                Object[] elements = list.stream().map(SqlRunner::scalar).toArray();
                try {
                    statement.setArray(i + 1, connection.createArrayOf(arrayType(elements), elements));
                } catch (SQLFeatureNotSupportedException noArray) {
                    statement.setObject(i + 1, elements);
                }
            } else {
                statement.setObject(i + 1, scalar(value));
            }
        }
    }

    /** A JSON value as it is bound: an integer that a long holds as a {@code Long}, any other number as it is. */
    private static Object scalar(Object value) {
        if (value instanceof BigDecimal number) {
            try {
                return number.longValueExact();
            } catch (ArithmeticException notALong) {
                return number;
            }
        }
        return value;
    }

    /** The SQL type of an array of these elements: BIGINT, NUMERIC, BOOLEAN, or VARCHAR for any other. */
    private static String arrayType(Object[] elements) {
        List<Object> values = Arrays.stream(elements).filter(Objects::nonNull).toList();
        if (!values.isEmpty() && values.stream().allMatch(Long.class::isInstance)) {
            return "BIGINT";
        }
        if (!values.isEmpty() && values.stream().allMatch(Number.class::isInstance)) {
            return "NUMERIC";
        }
        return !values.isEmpty() && values.stream().allMatch(Boolean.class::isInstance) ? "BOOLEAN" : "VARCHAR";
    }

    /** An error result: the database's message masked, or the cancellation of a statement past its timeout. */
    private ActionResult failure(SQLException failed, Map<String, Object> details) {
        boolean cancelled = failed instanceof SQLTimeoutException || CANCELLED.equals(failed.getSQLState());
        String message = cancelled ? "the statement ran past " + timeoutSeconds + " s and was cancelled"
                : masked(String.valueOf(failed.getMessage()));
        details.put("sqlState", failed.getSQLState());
        details.put("vendorCode", failed.getErrorCode());
        String line = message.replaceAll("\\s+", " ").strip();
        return new ActionResult(line.isEmpty() ? failed.getClass().getSimpleName() : line, ActionResult.TEXT, message,
                true, Json.write(details));
    }

    /** {@code text} with the pool's URL, its password and its user after the word user replaced by {@code ***}. */
    String masked(String text) {
        String out = text;
        if (url != null && !url.isBlank()) {
            out = out.replace(url, "***");
        }
        if (password != null && password.length() >= 4) {
            out = out.replace(password, "***");
        }
        if (user != null && !user.isBlank()) {
            out = Pattern.compile("(?i)(user\\s+\"?)" + Pattern.quote(user) + "(?![\\w])").matcher(out)
                    .replaceAll("$1***");
        }
        return out;
    }

    private static String cut(String name) {
        String text = name == null ? "" : name;
        return text.length() > MAX_NAME ? text.substring(0, MAX_NAME - 1) + "…" : text;
    }

    /** Sets the read-only flag of {@code connection}; a driver that refuses it is left as it is. */
    private static void readOnly(Connection connection, boolean on) {
        quietly(() -> connection.setReadOnly(on));
    }

    private interface Step {
        void run() throws SQLException;
    }

    private static void quietly(Step step) {
        try {
            step.run();
        } catch (SQLException | RuntimeException ignored) {
            // the connection goes back to the pool all the same, which validates it
        }
    }
}
