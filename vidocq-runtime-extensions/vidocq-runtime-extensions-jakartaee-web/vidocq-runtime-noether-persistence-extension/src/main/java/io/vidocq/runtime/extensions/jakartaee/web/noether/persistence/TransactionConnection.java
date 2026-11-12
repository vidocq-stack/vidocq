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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * The handle of a connection enlisted in a JTA transaction: closing it leaves the connection to the transaction, and
 * the transaction manager alone completes it, so a local {@code commit}, {@code rollback} or switch to auto-commit is
 * refused (JDBC 4.3, §10.4). A closed handle refuses any further work.
 */
final class TransactionConnection implements Connection {

    private final Connection physical;
    private volatile boolean closed;

    TransactionConnection(Connection physical) {
        this.physical = physical;
    }

    private Connection open() throws SQLException {
        if (closed) {
            throw new SQLException("The connection handle is closed");
        }
        return physical;
    }

    private static SQLException managed() {
        return new SQLException("The connection takes part in a JTA transaction: the transaction manager completes it");
    }

    @Override public void close() { closed = true; }
    @Override public boolean isClosed() { return closed; }
    @Override public void abort(Executor executor) { closed = true; }
    @Override public boolean isValid(int timeout) throws SQLException { return !closed && physical.isValid(timeout); }

    @Override public void commit() throws SQLException { open(); throw managed(); }
    @Override public void rollback() throws SQLException { open(); throw managed(); }
    @Override public void rollback(Savepoint savepoint) throws SQLException { open().rollback(savepoint); }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        if (autoCommit != open().getAutoCommit()) {
            throw managed();
        }
    }

    @Override public boolean getAutoCommit() throws SQLException { return open().getAutoCommit(); }
    @Override public Statement createStatement() throws SQLException { return open().createStatement(); }
    @Override public PreparedStatement prepareStatement(String sql) throws SQLException { return open().prepareStatement(sql); }
    @Override public CallableStatement prepareCall(String sql) throws SQLException { return open().prepareCall(sql); }
    @Override public String nativeSQL(String sql) throws SQLException { return open().nativeSQL(sql); }
    @Override public DatabaseMetaData getMetaData() throws SQLException { return open().getMetaData(); }
    @Override public void setReadOnly(boolean readOnly) throws SQLException { open().setReadOnly(readOnly); }
    @Override public boolean isReadOnly() throws SQLException { return open().isReadOnly(); }
    @Override public void setCatalog(String catalog) throws SQLException { open().setCatalog(catalog); }
    @Override public String getCatalog() throws SQLException { return open().getCatalog(); }
    @Override public void setTransactionIsolation(int level) throws SQLException { open().setTransactionIsolation(level); }
    @Override public int getTransactionIsolation() throws SQLException { return open().getTransactionIsolation(); }
    @Override public SQLWarning getWarnings() throws SQLException { return open().getWarnings(); }
    @Override public void clearWarnings() throws SQLException { open().clearWarnings(); }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return open().createStatement(resultSetType, resultSetConcurrency);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        return open().prepareStatement(sql, resultSetType, resultSetConcurrency);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return open().prepareCall(sql, resultSetType, resultSetConcurrency);
    }

    @Override public Map<String, Class<?>> getTypeMap() throws SQLException { return open().getTypeMap(); }
    @Override public void setTypeMap(Map<String, Class<?>> map) throws SQLException { open().setTypeMap(map); }
    @Override public void setHoldability(int holdability) throws SQLException { open().setHoldability(holdability); }
    @Override public int getHoldability() throws SQLException { return open().getHoldability(); }
    @Override public Savepoint setSavepoint() throws SQLException { return open().setSavepoint(); }
    @Override public Savepoint setSavepoint(String name) throws SQLException { return open().setSavepoint(name); }
    @Override public void releaseSavepoint(Savepoint savepoint) throws SQLException { open().releaseSavepoint(savepoint); }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        return open().createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
            int resultSetHoldability) throws SQLException {
        return open().prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
            int resultSetHoldability) throws SQLException {
        return open().prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        return open().prepareStatement(sql, autoGeneratedKeys);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        return open().prepareStatement(sql, columnIndexes);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        return open().prepareStatement(sql, columnNames);
    }

    @Override public Clob createClob() throws SQLException { return open().createClob(); }
    @Override public Blob createBlob() throws SQLException { return open().createBlob(); }
    @Override public NClob createNClob() throws SQLException { return open().createNClob(); }
    @Override public SQLXML createSQLXML() throws SQLException { return open().createSQLXML(); }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        return open().createArrayOf(typeName, elements);
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        return open().createStruct(typeName, attributes);
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        physical.setClientInfo(name, value);
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        physical.setClientInfo(properties);
    }

    @Override public String getClientInfo(String name) throws SQLException { return open().getClientInfo(name); }
    @Override public Properties getClientInfo() throws SQLException { return open().getClientInfo(); }
    @Override public void setSchema(String schema) throws SQLException { open().setSchema(schema); }
    @Override public String getSchema() throws SQLException { return open().getSchema(); }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        open().setNetworkTimeout(executor, milliseconds);
    }

    @Override public int getNetworkTimeout() throws SQLException { return open().getNetworkTimeout(); }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        return type.isInstance(this) ? type.cast(this) : open().unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this) || open().isWrapperFor(type);
    }
}
