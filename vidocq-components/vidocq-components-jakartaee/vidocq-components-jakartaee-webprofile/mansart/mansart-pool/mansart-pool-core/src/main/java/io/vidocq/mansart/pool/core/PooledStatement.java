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
package io.vidocq.mansart.pool.core;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;

/**
 * Wrapper around a driver {@link Statement} created through a {@link PooledConnection}. It exists for two reasons:
 * every {@code execute*} call tells the connection that work may now be pending, so the pool knows whether a
 * connection handed back out of auto-commit needs a rollback (Vidocq/noether#45); and {@link #getConnection()}
 * returns the pooled connection, not the driver's, so the client cannot reach around the pool.
 *
 * <p>The connection is marked <i>before</i> the driver runs the statement: a statement that fails half-way, a
 * batch in particular, may still have changed something. Every other method is a passthrough.
 *
 * @param <S> the driver statement type
 */
sealed class PooledStatement<S extends Statement> implements Statement permits PooledPreparedStatement {

    final S                delegate;
    final PooledConnection connection;

    PooledStatement(S delegate, PooledConnection connection) {
        this.delegate   = delegate;
        this.connection = connection;
    }

    /* ---- execution: work may be pending from here on ---- */

    @Override public ResultSet executeQuery(String sql) throws SQLException                     { connection.markWork(); return delegate.executeQuery(sql); }
    @Override public int       executeUpdate(String sql) throws SQLException                    { connection.markWork(); return delegate.executeUpdate(sql); }
    @Override public int       executeUpdate(String sql, int autoGenKeys) throws SQLException   { connection.markWork(); return delegate.executeUpdate(sql, autoGenKeys); }
    @Override public int       executeUpdate(String sql, int[] columnIndexes) throws SQLException
                                                                                                { connection.markWork(); return delegate.executeUpdate(sql, columnIndexes); }
    @Override public int       executeUpdate(String sql, String[] columnNames) throws SQLException
                                                                                                { connection.markWork(); return delegate.executeUpdate(sql, columnNames); }
    @Override public boolean   execute(String sql) throws SQLException                          { connection.markWork(); return delegate.execute(sql); }
    @Override public boolean   execute(String sql, int autoGenKeys) throws SQLException         { connection.markWork(); return delegate.execute(sql, autoGenKeys); }
    @Override public boolean   execute(String sql, int[] columnIndexes) throws SQLException     { connection.markWork(); return delegate.execute(sql, columnIndexes); }
    @Override public boolean   execute(String sql, String[] columnNames) throws SQLException    { connection.markWork(); return delegate.execute(sql, columnNames); }
    @Override public int[]     executeBatch() throws SQLException                               { connection.markWork(); return delegate.executeBatch(); }
    @Override public long[]    executeLargeBatch() throws SQLException                          { connection.markWork(); return delegate.executeLargeBatch(); }
    @Override public long      executeLargeUpdate(String sql) throws SQLException               { connection.markWork(); return delegate.executeLargeUpdate(sql); }
    @Override public long      executeLargeUpdate(String sql, int autoGenKeys) throws SQLException
                                                                                                { connection.markWork(); return delegate.executeLargeUpdate(sql, autoGenKeys); }
    @Override public long      executeLargeUpdate(String sql, int[] columnIndexes) throws SQLException
                                                                                                { connection.markWork(); return delegate.executeLargeUpdate(sql, columnIndexes); }
    @Override public long      executeLargeUpdate(String sql, String[] columnNames) throws SQLException
                                                                                                { connection.markWork(); return delegate.executeLargeUpdate(sql, columnNames); }

    /* ---- the pooled connection, never the driver's ---- */

    @Override public Connection getConnection()                                                 { return connection; }

    /* ---- passthrough ---- */

    @Override public void       close() throws SQLException                                     { delegate.close(); }
    @Override public boolean    isClosed() throws SQLException                                  { return delegate.isClosed(); }
    @Override public int        getMaxFieldSize() throws SQLException                           { return delegate.getMaxFieldSize(); }
    @Override public void       setMaxFieldSize(int max) throws SQLException                    { delegate.setMaxFieldSize(max); }
    @Override public int        getMaxRows() throws SQLException                                { return delegate.getMaxRows(); }
    @Override public void       setMaxRows(int max) throws SQLException                         { delegate.setMaxRows(max); }
    @Override public long       getLargeMaxRows() throws SQLException                           { return delegate.getLargeMaxRows(); }
    @Override public void       setLargeMaxRows(long max) throws SQLException                   { delegate.setLargeMaxRows(max); }
    @Override public void       setEscapeProcessing(boolean enable) throws SQLException         { delegate.setEscapeProcessing(enable); }
    @Override public int        getQueryTimeout() throws SQLException                           { return delegate.getQueryTimeout(); }
    @Override public void       setQueryTimeout(int seconds) throws SQLException                { delegate.setQueryTimeout(seconds); }
    @Override public void       cancel() throws SQLException                                    { delegate.cancel(); }
    @Override public SQLWarning getWarnings() throws SQLException                               { return delegate.getWarnings(); }
    @Override public void       clearWarnings() throws SQLException                             { delegate.clearWarnings(); }
    @Override public void       setCursorName(String name) throws SQLException                  { delegate.setCursorName(name); }
    @Override public ResultSet  getResultSet() throws SQLException                              { return delegate.getResultSet(); }
    @Override public int        getUpdateCount() throws SQLException                            { return delegate.getUpdateCount(); }
    @Override public long       getLargeUpdateCount() throws SQLException                       { return delegate.getLargeUpdateCount(); }
    @Override public boolean    getMoreResults() throws SQLException                            { return delegate.getMoreResults(); }
    @Override public boolean    getMoreResults(int current) throws SQLException                 { return delegate.getMoreResults(current); }
    @Override public void       setFetchDirection(int direction) throws SQLException            { delegate.setFetchDirection(direction); }
    @Override public int        getFetchDirection() throws SQLException                         { return delegate.getFetchDirection(); }
    @Override public void       setFetchSize(int rows) throws SQLException                      { delegate.setFetchSize(rows); }
    @Override public int        getFetchSize() throws SQLException                              { return delegate.getFetchSize(); }
    @Override public int        getResultSetConcurrency() throws SQLException                   { return delegate.getResultSetConcurrency(); }
    @Override public int        getResultSetType() throws SQLException                          { return delegate.getResultSetType(); }
    @Override public int        getResultSetHoldability() throws SQLException                   { return delegate.getResultSetHoldability(); }
    @Override public void       addBatch(String sql) throws SQLException                        { delegate.addBatch(sql); }
    @Override public void       clearBatch() throws SQLException                                { delegate.clearBatch(); }
    @Override public ResultSet  getGeneratedKeys() throws SQLException                          { return delegate.getGeneratedKeys(); }
    @Override public void       setPoolable(boolean poolable) throws SQLException               { delegate.setPoolable(poolable); }
    @Override public boolean    isPoolable() throws SQLException                                { return delegate.isPoolable(); }
    @Override public void       closeOnCompletion() throws SQLException                         { delegate.closeOnCompletion(); }
    @Override public boolean    isCloseOnCompletion() throws SQLException                       { return delegate.isCloseOnCompletion(); }
    @Override public String     enquoteLiteral(String val) throws SQLException                  { return delegate.enquoteLiteral(val); }
    @Override public String     enquoteIdentifier(String identifier, boolean alwaysQuote) throws SQLException
                                                                                                { return delegate.enquoteIdentifier(identifier, alwaysQuote); }
    @Override public boolean    isSimpleIdentifier(String identifier) throws SQLException       { return delegate.isSimpleIdentifier(identifier); }
    @Override public String     enquoteNCharLiteral(String val) throws SQLException             { return delegate.enquoteNCharLiteral(val); }

    /* ---- Wrapper: handing out the driver's statement hands out an untracked way to run SQL ---- */

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return (T) this;
        connection.markUntracked();
        if (iface.isInstance(delegate)) return (T) delegate;
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || iface.isInstance(delegate) || delegate.isWrapperFor(iface);
    }
}
