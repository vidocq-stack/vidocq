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

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.ShardingKey;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * Wrapper handed to the user on each {@link ConnectionPool#acquire()}. New instance per borrow:
 * once {@link #close()} returns, this wrapper is dead — even if the underlying {@link PooledEntry}
 * is later re-borrowed, that reuse goes through a fresh wrapper. A user holding a stale reference
 * always sees {@code isClosed() == true}.
 *
 * <p>The wrapper intercepts {@link #close()}, which returns the underlying entry to the pool instead
 * of closing the socket. It also tells the pool whether work may be pending when the connection comes
 * back out of auto-commit (Vidocq/noether#45): statements are wrapped ({@link PooledStatement}) so
 * that each {@code execute*} marks the connection, and {@link #commit()} / {@link #rollback()} clear
 * the mark. A transaction the client already ended then costs no second rollback on release.
 *
 * <p>Some calls hand out a driver object that can run SQL where the wrapper cannot see it: an
 * {@link #unwrap unwrapped} driver connection or statement, the {@link #getMetaData() metadata} (whose
 * {@code getConnection()} is the driver's), a {@link #prepareCall callable statement} (not wrapped),
 * a statement whose result sets are updatable. After any of them the borrow is <i>untracked</i> and
 * the pool rolls the connection back on release, as it always did. One path stays out of sight: a
 * driver {@code ResultSet}'s {@code getStatement()} returns the driver's statement, and SQL run on it
 * after the last commit is not seen.
 *
 * <p>Every other JDBC method is a pure passthrough; calling them after {@code close()} surfaces as
 * the standard JDBC error from the driver since we route to {@code delegate} unconditionally — the
 * misuse is observable.
 */
final class PooledConnection implements Connection {

    private final PooledEntry  entry;
    private final ConnectionPool pool;
    private final Connection   delegate;
    private volatile boolean   closed;
    /** A statement ran since the last {@link #commit()} or {@link #rollback()}. */
    private volatile boolean   workPending;
    /** A driver object that can run SQL out of the wrapper's sight was handed out: roll back on release. */
    private volatile boolean   untracked;

    PooledConnection(PooledEntry entry, ConnectionPool pool) {
        this.entry    = entry;
        this.pool     = pool;
        this.delegate = entry.delegate;
    }

    /* ---- close() interception (the only reason this wrapper exists) ---- */

    @Override
    public void close() throws SQLException {
        if (closed) return;
        closed = true;
        pool.release(entry, untracked || workPending);
    }

    /** Called before a wrapped statement runs: from here on, a release out of auto-commit rolls back. */
    void markWork() {
        if (!workPending) workPending = true;
    }

    /** Called when a driver object that can run SQL leaves the wrapper: the release always rolls back. */
    void markUntracked() {
        untracked = true;
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed || delegate.isClosed();
    }

    /* ---- Statement / PreparedStatement creation (wrapped: their execute* calls mark the connection) ---- */

    @Override public Statement createStatement() throws SQLException                         { return statement(delegate.createStatement()); }
    @Override public Statement createStatement(int rsType, int rsConcurrency) throws SQLException
                                                                                             { watch(rsConcurrency); return statement(delegate.createStatement(rsType, rsConcurrency)); }
    @Override public Statement createStatement(int rsType, int rsConcurrency, int rsHoldability) throws SQLException
                                                                                             { watch(rsConcurrency); return statement(delegate.createStatement(rsType, rsConcurrency, rsHoldability)); }

    @Override public PreparedStatement prepareStatement(String sql) throws SQLException                       { return prepared(delegate.prepareStatement(sql)); }
    @Override public PreparedStatement prepareStatement(String sql, int autoGenKeys) throws SQLException      { return prepared(delegate.prepareStatement(sql, autoGenKeys)); }
    @Override public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException  { return prepared(delegate.prepareStatement(sql, columnIndexes)); }
    @Override public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException { return prepared(delegate.prepareStatement(sql, columnNames)); }
    @Override public PreparedStatement prepareStatement(String sql, int rsType, int rsConcurrency) throws SQLException
                                                                                             { watch(rsConcurrency); return prepared(delegate.prepareStatement(sql, rsType, rsConcurrency)); }
    @Override public PreparedStatement prepareStatement(String sql, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
                                                                                             { watch(rsConcurrency); return prepared(delegate.prepareStatement(sql, rsType, rsConcurrency, rsHoldability)); }

    private Statement statement(Statement raw)                                               { return new PooledStatement<>(raw, this); }
    private PreparedStatement prepared(PreparedStatement raw)                                { return new PooledPreparedStatement(raw, this); }

    /** An updatable result set writes through {@code updateRow()}, which no statement wrapper sees. */
    private void watch(int rsConcurrency) {
        if (rsConcurrency != ResultSet.CONCUR_READ_ONLY) markUntracked();
    }

    /* ---- CallableStatement creation (passthrough, untracked: stored procedures are no hot path) ---- */

    @Override public CallableStatement prepareCall(String sql) throws SQLException                              { markUntracked(); return delegate.prepareCall(sql); }
    @Override public CallableStatement prepareCall(String sql, int rsType, int rsConcurrency) throws SQLException
                                                                                             { markUntracked(); return delegate.prepareCall(sql, rsType, rsConcurrency); }
    @Override public CallableStatement prepareCall(String sql, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
                                                                                             { markUntracked(); return delegate.prepareCall(sql, rsType, rsConcurrency, rsHoldability); }

    /* ---- Transaction / autoCommit / savepoints (commit and rollback end the pending work) ---- */

    @Override public void       setAutoCommit(boolean autoCommit) throws SQLException        { delegate.setAutoCommit(autoCommit); }
    @Override public boolean    getAutoCommit() throws SQLException                          { return delegate.getAutoCommit(); }
    @Override public void       commit() throws SQLException                                 { delegate.commit(); workPending = false; }
    @Override public void       rollback() throws SQLException                               { delegate.rollback(); workPending = false; }
    @Override public void       rollback(Savepoint savepoint) throws SQLException            { delegate.rollback(savepoint); }
    @Override public Savepoint  setSavepoint() throws SQLException                           { return delegate.setSavepoint(); }
    @Override public Savepoint  setSavepoint(String name) throws SQLException                { return delegate.setSavepoint(name); }
    @Override public void       releaseSavepoint(Savepoint savepoint) throws SQLException    { delegate.releaseSavepoint(savepoint); }
    @Override public void       setTransactionIsolation(int level) throws SQLException       { delegate.setTransactionIsolation(level); }
    @Override public int        getTransactionIsolation() throws SQLException                { return delegate.getTransactionIsolation(); }

    /* ---- Misc state (passthrough) ---- */

    /** Untracked: {@code DatabaseMetaData.getConnection()} is the driver's connection. */
    @Override public DatabaseMetaData getMetaData() throws SQLException                      { markUntracked(); return delegate.getMetaData(); }
    @Override public void       setReadOnly(boolean readOnly) throws SQLException            { delegate.setReadOnly(readOnly); }
    @Override public boolean    isReadOnly() throws SQLException                             { return delegate.isReadOnly(); }
    @Override public void       setCatalog(String catalog) throws SQLException               { delegate.setCatalog(catalog); }
    @Override public String     getCatalog() throws SQLException                             { return delegate.getCatalog(); }
    @Override public void       setSchema(String schema) throws SQLException                 { delegate.setSchema(schema); }
    @Override public String     getSchema() throws SQLException                              { return delegate.getSchema(); }
    @Override public SQLWarning getWarnings() throws SQLException                            { return delegate.getWarnings(); }
    @Override public void       clearWarnings() throws SQLException                          { delegate.clearWarnings(); }
    @Override public Map<String, Class<?>> getTypeMap() throws SQLException                  { return delegate.getTypeMap(); }
    @Override public void       setTypeMap(Map<String, Class<?>> map) throws SQLException    { delegate.setTypeMap(map); }
    @Override public void       setHoldability(int holdability) throws SQLException          { delegate.setHoldability(holdability); }
    @Override public int        getHoldability() throws SQLException                         { return delegate.getHoldability(); }
    @Override public boolean    isValid(int timeout) throws SQLException                     { return delegate.isValid(timeout); }
    @Override public String     nativeSQL(String sql) throws SQLException                    { return delegate.nativeSQL(sql); }

    /* ---- LOB / structured types (a large object may live in the database: work pending) ---- */

    @Override public Clob   createClob()    throws SQLException                              { markWork(); return delegate.createClob(); }
    @Override public Blob   createBlob()    throws SQLException                              { markWork(); return delegate.createBlob(); }
    @Override public NClob  createNClob()   throws SQLException                              { markWork(); return delegate.createNClob(); }
    @Override public SQLXML createSQLXML()  throws SQLException                              { markWork(); return delegate.createSQLXML(); }
    @Override public Array  createArrayOf(String typeName, Object[] elements) throws SQLException
                                                                                             { return delegate.createArrayOf(typeName, elements); }
    @Override public Struct createStruct(String typeName, Object[] attributes) throws SQLException
                                                                                             { return delegate.createStruct(typeName, attributes); }

    /* ---- Client info (passthrough) ---- */

    @Override public void   setClientInfo(String name, String value) throws SQLClientInfoException
                                                                                             { delegate.setClientInfo(name, value); }
    @Override public void   setClientInfo(Properties properties) throws SQLClientInfoException
                                                                                             { delegate.setClientInfo(properties); }
    @Override public String getClientInfo(String name) throws SQLException                   { return delegate.getClientInfo(name); }
    @Override public Properties getClientInfo() throws SQLException                          { return delegate.getClientInfo(); }

    /* ---- Network / timeout / abort (passthrough) ---- */

    @Override public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException
                                                                                             { delegate.setNetworkTimeout(executor, milliseconds); }
    @Override public int  getNetworkTimeout() throws SQLException                            { return delegate.getNetworkTimeout(); }
    @Override public void abort(Executor executor) throws SQLException                       { delegate.abort(executor); }

    /* ---- JDBC 4.3 hint methods ---- */

    @Override public void beginRequest() throws SQLException                                 { delegate.beginRequest(); }
    @Override public void endRequest()   throws SQLException                                 { delegate.endRequest(); }
    @Override public boolean setShardingKeyIfValid(ShardingKey shardingKey, ShardingKey superShardingKey, int timeout) throws SQLException
                                                                                             { return delegate.setShardingKeyIfValid(shardingKey, superShardingKey, timeout); }
    @Override public boolean setShardingKeyIfValid(ShardingKey shardingKey, int timeout) throws SQLException
                                                                                             { return delegate.setShardingKeyIfValid(shardingKey, timeout); }
    @Override public void setShardingKey(ShardingKey shardingKey, ShardingKey superShardingKey) throws SQLException
                                                                                             { delegate.setShardingKey(shardingKey, superShardingKey); }
    @Override public void setShardingKey(ShardingKey shardingKey) throws SQLException        { delegate.setShardingKey(shardingKey); }

    /* ---- Wrapper: handing out the driver's connection hands out an untracked way to run SQL ---- */

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this))      return (T) this;
        markUntracked();
        if (iface.isInstance(delegate))  return (T) delegate;
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || iface.isInstance(delegate) || delegate.isWrapperFor(iface);
    }
}
