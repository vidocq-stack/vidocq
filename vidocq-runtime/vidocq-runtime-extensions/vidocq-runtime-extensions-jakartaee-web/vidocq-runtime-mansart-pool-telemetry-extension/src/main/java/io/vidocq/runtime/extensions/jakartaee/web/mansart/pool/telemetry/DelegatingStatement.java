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

/**
 * A {@link java.sql.Statement} whose {@code execute*} calls go through {@link JdbcExecution}.
 *
 * <p>Generated from {@link java.sql.Statement} by {@code JdbcDelegatesGenerator} (test sources): do
 * not edit. Regenerate with {@code ./mvnw -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/
 * vidocq-runtime-mansart-pool-telemetry-extension test -Dtest=JdbcDelegatesTest -Dvidocq.jdbc.delegates.write=true}.</p>
 */
@SuppressWarnings({"deprecation", "removal"})
final class DelegatingStatement implements java.sql.Statement {

    private final java.sql.Statement delegate;
    private final JdbcExecution execution;
    private final java.sql.Connection connection;
    private final String sql;
    private final java.util.List<String> batch = new java.util.ArrayList<>();

    DelegatingStatement(java.sql.Statement delegate, JdbcExecution execution, java.sql.Connection connection, String sql) {
        this.delegate = delegate;
        this.execution = execution;
        this.connection = connection;
        this.sql = sql;
    }

    /** The SQL a batch runs: the statements added to a plain statement's batch, else the prepared SQL. */
    private String batchSql() {
        return batch.isEmpty() ? sql : String.join("; ", batch);
    }

    @Override
    public void addBatch(java.lang.String p0) throws java.sql.SQLException {
        batch.add(p0);
        delegate.addBatch(p0);
    }

    @Override
    public void cancel() throws java.sql.SQLException {
        delegate.cancel();
    }

    @Override
    public void clearBatch() throws java.sql.SQLException {
        batch.clear();
        delegate.clearBatch();
    }

    @Override
    public void clearWarnings() throws java.sql.SQLException {
        delegate.clearWarnings();
    }

    @Override
    public void close() throws java.sql.SQLException {
        delegate.close();
    }

    @Override
    public void closeOnCompletion() throws java.sql.SQLException {
        delegate.closeOnCompletion();
    }

    @Override
    public java.lang.String enquoteIdentifier(java.lang.String p0, boolean p1) throws java.sql.SQLException {
        return delegate.enquoteIdentifier(p0, p1);
    }

    @Override
    public java.lang.String enquoteLiteral(java.lang.String p0) throws java.sql.SQLException {
        return delegate.enquoteLiteral(p0);
    }

    @Override
    public java.lang.String enquoteNCharLiteral(java.lang.String p0) throws java.sql.SQLException {
        return delegate.enquoteNCharLiteral(p0);
    }

    @Override
    public boolean execute(java.lang.String p0, int[] p1) throws java.sql.SQLException {
        return execution.execute(p0, false, () -> delegate.execute(p0, p1));
    }

    @Override
    public boolean execute(java.lang.String p0, java.lang.String[] p1) throws java.sql.SQLException {
        return execution.execute(p0, false, () -> delegate.execute(p0, p1));
    }

    @Override
    public boolean execute(java.lang.String p0, int p1) throws java.sql.SQLException {
        return execution.execute(p0, false, () -> delegate.execute(p0, p1));
    }

    @Override
    public boolean execute(java.lang.String p0) throws java.sql.SQLException {
        return execution.execute(p0, false, () -> delegate.execute(p0));
    }

    @Override
    public int[] executeBatch() throws java.sql.SQLException {
        try {
            return execution.execute(batchSql(), batch.isEmpty(), () -> delegate.executeBatch());
        } finally {
            batch.clear();
        }
    }

    @Override
    public long[] executeLargeBatch() throws java.sql.SQLException {
        try {
            return execution.execute(batchSql(), batch.isEmpty(), () -> delegate.executeLargeBatch());
        } finally {
            batch.clear();
        }
    }

    @Override
    public long executeLargeUpdate(java.lang.String p0, int[] p1) throws java.sql.SQLException {
        return execution.execute(p0, false, () -> delegate.executeLargeUpdate(p0, p1));
    }

    @Override
    public long executeLargeUpdate(java.lang.String p0, java.lang.String[] p1) throws java.sql.SQLException {
        return execution.execute(p0, false, () -> delegate.executeLargeUpdate(p0, p1));
    }

    @Override
    public long executeLargeUpdate(java.lang.String p0, int p1) throws java.sql.SQLException {
        return execution.execute(p0, false, () -> delegate.executeLargeUpdate(p0, p1));
    }

    @Override
    public long executeLargeUpdate(java.lang.String p0) throws java.sql.SQLException {
        return execution.execute(p0, false, () -> delegate.executeLargeUpdate(p0));
    }

    @Override
    public java.sql.ResultSet executeQuery(java.lang.String p0) throws java.sql.SQLException {
        return execution.execute(p0, false, () -> delegate.executeQuery(p0));
    }

    @Override
    public int executeUpdate(java.lang.String p0, int[] p1) throws java.sql.SQLException {
        return execution.execute(p0, false, () -> delegate.executeUpdate(p0, p1));
    }

    @Override
    public int executeUpdate(java.lang.String p0, java.lang.String[] p1) throws java.sql.SQLException {
        return execution.execute(p0, false, () -> delegate.executeUpdate(p0, p1));
    }

    @Override
    public int executeUpdate(java.lang.String p0, int p1) throws java.sql.SQLException {
        return execution.execute(p0, false, () -> delegate.executeUpdate(p0, p1));
    }

    @Override
    public int executeUpdate(java.lang.String p0) throws java.sql.SQLException {
        return execution.execute(p0, false, () -> delegate.executeUpdate(p0));
    }

    @Override
    public java.sql.Connection getConnection() throws java.sql.SQLException {
        return connection;
    }

    @Override
    public int getFetchDirection() throws java.sql.SQLException {
        return delegate.getFetchDirection();
    }

    @Override
    public int getFetchSize() throws java.sql.SQLException {
        return delegate.getFetchSize();
    }

    @Override
    public java.sql.ResultSet getGeneratedKeys() throws java.sql.SQLException {
        return delegate.getGeneratedKeys();
    }

    @Override
    public long getLargeMaxRows() throws java.sql.SQLException {
        return delegate.getLargeMaxRows();
    }

    @Override
    public long getLargeUpdateCount() throws java.sql.SQLException {
        return delegate.getLargeUpdateCount();
    }

    @Override
    public int getMaxFieldSize() throws java.sql.SQLException {
        return delegate.getMaxFieldSize();
    }

    @Override
    public int getMaxRows() throws java.sql.SQLException {
        return delegate.getMaxRows();
    }

    @Override
    public boolean getMoreResults() throws java.sql.SQLException {
        return delegate.getMoreResults();
    }

    @Override
    public boolean getMoreResults(int p0) throws java.sql.SQLException {
        return delegate.getMoreResults(p0);
    }

    @Override
    public int getQueryTimeout() throws java.sql.SQLException {
        return delegate.getQueryTimeout();
    }

    @Override
    public java.sql.ResultSet getResultSet() throws java.sql.SQLException {
        return delegate.getResultSet();
    }

    @Override
    public int getResultSetConcurrency() throws java.sql.SQLException {
        return delegate.getResultSetConcurrency();
    }

    @Override
    public int getResultSetHoldability() throws java.sql.SQLException {
        return delegate.getResultSetHoldability();
    }

    @Override
    public int getResultSetType() throws java.sql.SQLException {
        return delegate.getResultSetType();
    }

    @Override
    public int getUpdateCount() throws java.sql.SQLException {
        return delegate.getUpdateCount();
    }

    @Override
    public java.sql.SQLWarning getWarnings() throws java.sql.SQLException {
        return delegate.getWarnings();
    }

    @Override
    public boolean isCloseOnCompletion() throws java.sql.SQLException {
        return delegate.isCloseOnCompletion();
    }

    @Override
    public boolean isClosed() throws java.sql.SQLException {
        return delegate.isClosed();
    }

    @Override
    public boolean isPoolable() throws java.sql.SQLException {
        return delegate.isPoolable();
    }

    @Override
    public boolean isSimpleIdentifier(java.lang.String p0) throws java.sql.SQLException {
        return delegate.isSimpleIdentifier(p0);
    }

    @Override
    public boolean isWrapperFor(java.lang.Class<?> p0) throws java.sql.SQLException {
        return p0.isInstance(delegate) || delegate.isWrapperFor(p0);
    }

    @Override
    public void setCursorName(java.lang.String p0) throws java.sql.SQLException {
        delegate.setCursorName(p0);
    }

    @Override
    public void setEscapeProcessing(boolean p0) throws java.sql.SQLException {
        delegate.setEscapeProcessing(p0);
    }

    @Override
    public void setFetchDirection(int p0) throws java.sql.SQLException {
        delegate.setFetchDirection(p0);
    }

    @Override
    public void setFetchSize(int p0) throws java.sql.SQLException {
        delegate.setFetchSize(p0);
    }

    @Override
    public void setLargeMaxRows(long p0) throws java.sql.SQLException {
        delegate.setLargeMaxRows(p0);
    }

    @Override
    public void setMaxFieldSize(int p0) throws java.sql.SQLException {
        delegate.setMaxFieldSize(p0);
    }

    @Override
    public void setMaxRows(int p0) throws java.sql.SQLException {
        delegate.setMaxRows(p0);
    }

    @Override
    public void setPoolable(boolean p0) throws java.sql.SQLException {
        delegate.setPoolable(p0);
    }

    @Override
    public void setQueryTimeout(int p0) throws java.sql.SQLException {
        delegate.setQueryTimeout(p0);
    }

    @Override
    public <T> T unwrap(java.lang.Class<T> p0) throws java.sql.SQLException {
        return p0.isInstance(delegate) ? p0.cast(delegate) : delegate.unwrap(p0);
    }
}
