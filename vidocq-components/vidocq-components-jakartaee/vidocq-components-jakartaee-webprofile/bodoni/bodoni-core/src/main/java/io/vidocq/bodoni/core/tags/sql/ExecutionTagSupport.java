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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import io.vidocq.bodoni.core.internal.Scopes;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspTagException;
import jakarta.servlet.jsp.jstl.sql.SQLExecutionTag;
import jakarta.servlet.jsp.tagext.BodyTagSupport;

/**
 * The actions that execute one SQL statement, {@code <sql:query>} and {@code <sql:update>}
 * (Tags 3.0 §10.3, §10.4): the statement is the {@code sql} attribute or, without it, the
 * trimmed body; nested parameter actions bind its markers through {@link #addSQLParameter}.
 * Inside {@code <sql:transaction>} the transaction's connection is used and left open;
 * otherwise a connection is obtained as §10.2 describes and closed before the action ends.
 *
 * <p>The attributes persist until {@link #release()}; the parameters and the body content
 * belong to one invocation and are reset by {@link #doStartTag()}. The public setters reach
 * other packages through the bridges javac emits in each public handler.
 */
abstract class ExecutionTagSupport extends BodyTagSupport implements SQLExecutionTag {

    private static final long serialVersionUID = 1L;

    private final String action;
    private String sql;
    private transient Object dataSource;
    private boolean dataSourceSpecified;
    private String varName;
    private String scope;
    private transient List<Object> parameters = new ArrayList<>();

    /** @param action the action name, for messages */
    ExecutionTagSupport(String action) {
        this.action = action;
    }

    /** @param sql the SQL statement; the body then only runs the nested parameter actions */
    public void setSql(String sql) {
        this.sql = sql;
    }

    /** @param dataSource a {@code javax.sql.DataSource}, a JNDI path or a JDBC parameter string */
    public void setDataSource(Object dataSource) {
        this.dataSource = dataSource;
        this.dataSourceSpecified = true;
    }

    /** @param name the scoped variable receiving the result of the statement */
    public void setVar(String name) {
        this.varName = name;
    }

    /** @param scope the scope of {@code var} */
    public void setScope(String scope) {
        this.scope = scope;
    }

    /**
     * Appends a value for the next parameter marker, unmodified; a nested parameter action
     * calls it during the body.
     */
    @Override
    public void addSQLParameter(Object value) {
        parameters.add(value);
    }

    @Override
    public int doStartTag() throws JspException {
        // An empty element gets no setBodyContent: never reuse a pooled handler's old body.
        bodyContent = null;
        parameters = new ArrayList<>();
        scopeId();
        if (varName != null && varName.isEmpty()) {
            throw new JspTagException(action + ": var must not be empty");
        }
        if (scope != null && varName == null) {
            throw new JspTagException(action + ": scope is set but var is not");
        }
        if (dataSourceSpecified && findAncestorWithClass(this, TransactionTag.class) != null) {
            throw new JspTagException(action + ": dataSource must not be specified inside <sql:transaction>");
        }
        return EVAL_BODY_BUFFERED;
    }

    @Override
    public int doEndTag() throws JspException {
        String statement = statement();
        TransactionTag transaction = (TransactionTag) findAncestorWithClass(this, TransactionTag.class);
        Object result = transaction == null ? executeOwned(statement) : executeShared(transaction, statement);
        if (varName != null) {
            pageContext.setAttribute(varName, result, scopeId());
        }
        return EVAL_PAGE;
    }

    @Override
    public void release() {
        super.release();
        sql = null;
        dataSource = null;
        dataSourceSpecified = false;
        varName = null;
        scope = null;
        parameters = new ArrayList<>();
    }

    /**
     * Executes the prepared and bound statement.
     *
     * @param statement the statement, closed by the caller
     * @return the value exported to {@code var}
     * @throws SQLException when the database fails
     */
    protected abstract Object execute(PreparedStatement statement) throws SQLException;

    /** Whether {@code var} was set. */
    final boolean hasVar() {
        return varName != null;
    }

    /** The {@code sql} attribute when set, else the trimmed body. */
    private String statement() throws JspTagException {
        String text = sql != null ? sql : body();
        if (text == null || text.isBlank()) {
            throw new JspTagException(action + ": the SQL statement is empty");
        }
        return text;
    }

    private String body() {
        return bodyContent == null ? null : bodyContent.getString().trim();
    }

    /**
     * Runs {@code statement} on a connection of its own, closed here. A failure to get or to
     * close it names the statement (§10.3, §10.4); a failure to close it after a failing
     * statement is suppressed on the statement's failure.
     */
    private Object executeOwned(String statement) throws JspException {
        SqlSupport.Opened opened =
                SqlSupport.open(action, dataSourceSpecified, dataSource, pageContext, statement);
        try (Connection connection = opened.connection()) {
            return executeOn(connection, statement);
        } catch (SQLException e) {
            // executeOn turns its own SQLExceptions into JspExceptions: this one comes from close()
            throw new JspException(action + ": cannot close the connection to " + opened.source() + " after \""
                    + statement + "\": " + e.getMessage(), e);
        }
    }

    /** Runs {@code statement} on the connection of the enclosing transaction, left open. */
    private Object executeShared(TransactionTag transaction, String statement) throws JspException {
        Connection connection = transaction.connection();
        if (connection == null) {
            throw new IllegalStateException("an enclosing sql:transaction always holds an open connection "
                    + "while its body runs: its doStartTag opens it before the body");
        }
        return executeOn(connection, statement);
    }

    private Object executeOn(Connection connection, String statement) throws JspException {
        // The page author's statement, spec §10.3; values go through sql:param.
        try (PreparedStatement prepared = connection.prepareStatement(statement)) {
            SqlSupport.bind(prepared, parameters);
            return execute(prepared);
        } catch (SQLException e) {
            throw failure(statement, e);
        }
    }

    private JspException failure(String statement, SQLException e) {
        return new JspException(action + ": cannot execute \"" + statement + "\": " + e.getMessage(), e);
    }

    private int scopeId() throws JspTagException {
        try {
            return Scopes.of(scope);
        } catch (IllegalArgumentException e) {
            throw new JspTagException(action + ": " + e.getMessage(), e);
        }
    }
}
