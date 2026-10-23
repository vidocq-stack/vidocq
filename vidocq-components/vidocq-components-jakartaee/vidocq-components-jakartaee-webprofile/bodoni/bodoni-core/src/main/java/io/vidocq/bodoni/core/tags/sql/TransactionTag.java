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
import java.sql.SQLException;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.tagext.TagSupport;
import jakarta.servlet.jsp.tagext.TryCatchFinally;

/**
 * {@code <sql:transaction>} (Tags 3.0 §10.5): runs the nested {@code <sql:query>} and
 * {@code <sql:update>} actions on one connection, committed by {@link #doEndTag()} or rolled
 * back by {@link #doCatch(Throwable)}. {@link #doFinally()} restores the isolation level and
 * the auto-commit mode it changed, then closes the connection; a failure there is logged to
 * the servlet context, never thrown.
 *
 * <p>The attributes persist until {@link #release()}; the connection and the saved settings
 * belong to one invocation and are reset by {@link #doFinally()}.
 */
public class TransactionTag extends TagSupport implements TryCatchFinally {

    private static final long serialVersionUID = 1L;

    private static final String ACTION = "sql:transaction";

    /** No isolation level given, or none saved. */
    private static final int UNSET = -1;

    private transient Object dataSource;
    private boolean dataSourceSpecified;
    private String isolation;

    private transient Connection connection;
    private boolean begun;
    private boolean savedAutoCommit;
    private int savedIsolation = UNSET;

    /** A cleanup step of {@link #doFinally()}. */
    @FunctionalInterface
    private interface Step {
        void run() throws SQLException;
    }

    /** @param dataSource a {@code javax.sql.DataSource}, a JNDI path or a JDBC parameter string */
    public void setDataSource(Object dataSource) {
        this.dataSource = dataSource;
        this.dataSourceSpecified = true;
    }

    /**
     * @param isolation {@code read_committed}, {@code read_uncommitted}, {@code repeatable_read}
     *                  or {@code serializable}; {@code null} keeps the data source's level
     */
    public void setIsolation(String isolation) {
        this.isolation = isolation;
    }

    /** The open connection of the current invocation, {@code null} outside one. */
    Connection connection() {
        return connection;
    }

    @Override
    public int doStartTag() throws JspException {
        int level = level(isolation);
        connection = SqlSupport.open(ACTION, dataSourceSpecified, dataSource, pageContext).connection();
        try {
            begin(level);
        } catch (SQLException e) {
            throw new JspException(ACTION + ": cannot start the transaction: " + e.getMessage(), e);
        }
        return EVAL_BODY_INCLUDE;
    }

    @Override
    public int doEndTag() throws JspException {
        try {
            connection.commit();
        } catch (SQLException e) {
            throw new JspException(ACTION + ": commit failed: " + e.getMessage(), e);
        }
        return EVAL_PAGE;
    }

    /** Rolls the transaction back when it has begun, then rethrows {@code t}. */
    @Override
    public void doCatch(Throwable t) throws Throwable {
        if (begun) {
            try {
                connection.rollback();
            } catch (SQLException e) {
                t.addSuppressed(e);
            }
        }
        throw t;
    }

    @Override
    public void doFinally() {
        if (connection != null) {
            if (savedIsolation != UNSET) {
                quietly("cannot restore the transaction isolation",
                        () -> connection.setTransactionIsolation(savedIsolation));
            }
            if (begun) {
                quietly("cannot restore the auto-commit mode", () -> connection.setAutoCommit(savedAutoCommit));
            }
            quietly("cannot close the connection", connection::close);
        }
        connection = null;
        begun = false;
        savedAutoCommit = false;
        savedIsolation = UNSET;
    }

    @Override
    public void release() {
        super.release();
        dataSource = null;
        dataSourceSpecified = false;
        isolation = null;
    }

    /** Tags 3.0 §10.5 {@code doStartTag()}: checks transactions are supported, then opens one. */
    private void begin(int level) throws SQLException, JspTagException {
        int current = connection.getTransactionIsolation();
        if (current == Connection.TRANSACTION_NONE) {
            throw new JspTagException(ACTION + ": the data source does not support transactions (TRANSACTION_NONE)");
        }
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        savedAutoCommit = autoCommit;
        begun = true;
        if (level != UNSET && level != current) {
            savedIsolation = current;
            connection.setTransactionIsolation(level);
        }
    }

    private void quietly(String what, Step step) {
        try {
            step.run();
        } catch (SQLException | RuntimeException e) {
            pageContext.getServletContext().log(ACTION + ": " + what + ": " + e.getMessage(), e);
        }
    }

    private static int level(String isolation) throws JspTagException {
        if (isolation == null) {
            return UNSET;
        }
        return switch (isolation) {
            case "read_committed" -> Connection.TRANSACTION_READ_COMMITTED;
            case "read_uncommitted" -> Connection.TRANSACTION_READ_UNCOMMITTED;
            case "repeatable_read" -> Connection.TRANSACTION_REPEATABLE_READ;
            case "serializable" -> Connection.TRANSACTION_SERIALIZABLE;
            default -> throw new JspTagException(ACTION + ": invalid isolation '" + isolation
                    + "': expected read_committed, read_uncommitted, repeatable_read or serializable");
        };
    }
}
