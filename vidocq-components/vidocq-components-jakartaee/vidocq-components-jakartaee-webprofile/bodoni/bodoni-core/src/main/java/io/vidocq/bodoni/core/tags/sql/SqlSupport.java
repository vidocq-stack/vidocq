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

package io.vidocq.bodoni.core.tags.sql;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;
import java.util.List;

import javax.sql.DataSource;

import io.vidocq.bodoni.core.internal.DataSources;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.jstl.core.Config;
import jakarta.servlet.jsp.jstl.sql.SQLExecutionTag;
import jakarta.servlet.jsp.tagext.Tag;
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * What the {@code sql:} actions share: the data-source algorithm of Tags 3.0 §10.2, the
 * {@code maxRows} configuration setting (§10.9.2, §2.8) and parameter binding (§10.7).
 */
final class SqlSupport {

    /** Message of a specified {@code null} data source, after the action name. */
    private static final String NULL_DATA_SOURCE = ": dataSource is null";

    /** Message when neither the attribute nor the setting gives a data source, after the action name. */
    private static final String NO_DATA_SOURCE = ": no data source: neither the dataSource attribute nor "
            + Config.SQL_DATA_SOURCE + " is set";

    /** Message of an invalid {@code maxRows} setting: the value. */
    private static final String INVALID_MAX_ROWS = "sql:query: " + Config.SQL_MAX_ROWS
            + " must be an integer of -1 or more, got '%s'";

    private SqlSupport() {
    }

    /**
     * A connection the caller owns and closes, with the description of its source for messages.
     *
     * @param connection the open connection
     * @param source     the data source the page gave, described without credentials
     */
    record Opened(Connection connection, String source) {
    }

    /**
     * Opens a connection on the data source of an action, Tags 3.0 §10.2: the {@code dataSource}
     * attribute when it was specified, else the {@link Config#SQL_DATA_SOURCE} setting.
     *
     * @param action    the action name, for messages
     * @param specified whether the {@code dataSource} attribute was set, even to {@code null}
     * @param attribute the attribute value
     * @param pc        the page context the setting is looked up in
     * @param statement the SQL statement the connection is for, named in a connection failure
     *                  (§10.3, §10.4); {@code null} for {@code <sql:transaction>}
     * @return the connection, which the caller closes, and its described source
     * @throws JspException when the attribute is {@code null}, nothing is configured, the source
     *                      cannot be resolved or no connection can be obtained
     */
    static Opened open(String action, boolean specified, Object attribute, PageContext pc, String statement)
            throws JspException {
        Object source = source(action, specified, attribute, pc);
        return new Opened(connect(action, resolve(action, source), source, statement), DataSources.describe(source));
    }

    /**
     * Opens a connection; the caller closes it.
     *
     * @param action the action name, for messages
     * @param ds     the data source
     * @param source what the page gave, described in a failure without its credentials
     * @param statement the SQL statement the connection is for, named in a failure; {@code null}
     *                  when there is none
     * @return the connection
     * @throws JspException when the data source fails, with its exception as the cause
     */
    static Connection connect(String action, DataSource ds, Object source, String statement)
            throws JspException {
        String failure = action + ": cannot get a connection from " + DataSources.describe(source)
                + (statement == null ? "" : " to execute \"" + statement + "\"") + ": ";
        try {
            return ds.getConnection();
        } catch (SQLException e) {
            throw new JspException(failure + e.getMessage(), e);
        }
    }

    /**
     * The {@link Config#SQL_MAX_ROWS} setting: an {@code Integer}, {@code Short}, {@code Byte}
     * or a {@code Long} within the {@code int} range, or a {@code String} holding an integer;
     * unset or blank means no limit.
     *
     * @return the setting, {@code -1} for no limit
     * @throws JspTagException for another type (another {@code Number} included), a non-integer
     *                         string, a {@code Long} outside the {@code int} range or a value
     *                         below {@code -1}
     */
    static int maxRowsSetting(PageContext pc) throws JspTagException {
        Object value = Config.find(pc, Config.SQL_MAX_ROWS);
        int rows;
        if (value == null) {
            return -1;
        } else if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            rows = ((Number) value).intValue();
        } else if (value instanceof Long number && number == number.intValue()) {
            rows = number.intValue();
        } else if (value instanceof String text && text.isBlank()) {
            return -1;
        } else if (value instanceof String text) {
            rows = parse(text.trim(), value);
        } else {
            throw invalidMaxRows(value);
        }
        if (rows < -1) {
            throw invalidMaxRows(value);
        }
        return rows;
    }

    /**
     * Binds {@code parameters} to the markers of {@code statement}, in order. A {@code null}
     * value is bound as SQL {@code NULL} of the type the driver reports for the marker,
     * {@link Types#NULL} when it cannot tell (Tags 3.0 §10.7). A {@code java.sql.Date},
     * {@code Time} or {@code Timestamp} is bound with a default-zone {@link Calendar}, which JDBC
     * defines as equivalent to the plain setter, because some drivers (H2) convert the plain form
     * with {@code java.time} zone rules, which differ from the legacy rules for old dates
     * (BUG.md BOD-005).
     */
    static void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            Object value = parameters.get(i);
            int index = i + 1;
            switch (value) {
                case null -> statement.setNull(index, nullType(statement, index));
                // A fresh Calendar per value: Calendar is not thread-safe.
                case Timestamp stamp -> statement.setTimestamp(index, stamp, Calendar.getInstance());
                case Time time -> statement.setTime(index, time, Calendar.getInstance());
                case Date date -> statement.setDate(index, date, Calendar.getInstance());
                default -> statement.setObject(index, value);
            }
        }
    }

    private static Object source(String action, boolean specified, Object attribute, PageContext pc)
            throws JspException {
        if (specified) {
            if (attribute == null) {
                throw new JspException(action + NULL_DATA_SOURCE);
            }
            return attribute;
        }
        Object configured = Config.find(pc, Config.SQL_DATA_SOURCE);
        if (configured == null) {
            throw new JspException(action + NO_DATA_SOURCE);
        }
        return configured;
    }

    /**
     * The nearest enclosing {@link SQLExecutionTag}, which receives a parameter value.
     *
     * @param from    the parameter action
     * @param element the action as written in a page, for the message
     * @return the enclosing action
     * @throws JspTagException when no enclosing action accepts parameters
     */
    static SQLExecutionTag executionAncestor(Tag from, String element) throws JspTagException {
        if (TagSupport.findAncestorWithClass(from, SQLExecutionTag.class) instanceof SQLExecutionTag target) {
            return target;
        }
        throw new JspTagException("<" + element + "> must be nested in an action that implements SQLExecutionTag");
    }

    /**
     * Resolves a data source object or string with {@link DataSources#resolve}, a failure
     * becoming a {@link JspException} named after the action.
     */
    static DataSource resolve(String action, Object source) throws JspException {
        try {
            return DataSources.resolve(source);
        } catch (SQLException e) {
            throw new JspException(action + ": " + e.getMessage(), e);
        }
    }

    private static int parse(String text, Object value) throws JspTagException {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException _) {
            throw invalidMaxRows(value);
        }
    }

    private static JspTagException invalidMaxRows(Object value) {
        return new JspTagException(INVALID_MAX_ROWS.formatted(value));
    }

    private static int nullType(PreparedStatement statement, int index) {
        try {
            return statement.getParameterMetaData().getParameterType(index);
        } catch (SQLException _) {
            return Types.NULL;
        }
    }
}
