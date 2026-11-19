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
 * A {@link java.sql.Connection} that hands out traced statements and delegates everything else.
 *
 * <p>Generated from {@link java.sql.Connection} by {@code JdbcDelegatesGenerator} (test sources): do
 * not edit. Regenerate with {@code ./mvnw -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/
 * vidocq-runtime-mansart-pool-telemetry-extension test -Dtest=JdbcDelegatesTest -Dvidocq.jdbc.delegates.write=true}.</p>
 */
@SuppressWarnings({"deprecation", "removal"})
final class DelegatingConnection implements java.sql.Connection {

    private final java.sql.Connection delegate;
    private final JdbcExecution execution;

    DelegatingConnection(java.sql.Connection delegate, JdbcExecution execution) {
        this.delegate = delegate;
        this.execution = execution;
    }

    @Override
    public void abort(java.util.concurrent.Executor p0) throws java.sql.SQLException {
        delegate.abort(p0);
    }

    @Override
    public void beginRequest() throws java.sql.SQLException {
        delegate.beginRequest();
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
    public void commit() throws java.sql.SQLException {
        delegate.commit();
    }

    @Override
    public java.sql.Array createArrayOf(java.lang.String p0, java.lang.Object[] p1) throws java.sql.SQLException {
        return delegate.createArrayOf(p0, p1);
    }

    @Override
    public java.sql.Blob createBlob() throws java.sql.SQLException {
        return delegate.createBlob();
    }

    @Override
    public java.sql.Clob createClob() throws java.sql.SQLException {
        return delegate.createClob();
    }

    @Override
    public java.sql.NClob createNClob() throws java.sql.SQLException {
        return delegate.createNClob();
    }

    @Override
    public java.sql.SQLXML createSQLXML() throws java.sql.SQLException {
        return delegate.createSQLXML();
    }

    @Override
    public java.sql.Statement createStatement() throws java.sql.SQLException {
        return new DelegatingStatement(delegate.createStatement(), execution, this, null);
    }

    @Override
    public java.sql.Statement createStatement(int p0, int p1, int p2) throws java.sql.SQLException {
        return new DelegatingStatement(delegate.createStatement(p0, p1, p2), execution, this, null);
    }

    @Override
    public java.sql.Statement createStatement(int p0, int p1) throws java.sql.SQLException {
        return new DelegatingStatement(delegate.createStatement(p0, p1), execution, this, null);
    }

    @Override
    public java.sql.Struct createStruct(java.lang.String p0, java.lang.Object[] p1) throws java.sql.SQLException {
        return delegate.createStruct(p0, p1);
    }

    @Override
    public void endRequest() throws java.sql.SQLException {
        delegate.endRequest();
    }

    @Override
    public boolean getAutoCommit() throws java.sql.SQLException {
        return delegate.getAutoCommit();
    }

    @Override
    public java.lang.String getCatalog() throws java.sql.SQLException {
        return delegate.getCatalog();
    }

    @Override
    public java.util.Properties getClientInfo() throws java.sql.SQLException {
        return delegate.getClientInfo();
    }

    @Override
    public java.lang.String getClientInfo(java.lang.String p0) throws java.sql.SQLException {
        return delegate.getClientInfo(p0);
    }

    @Override
    public int getHoldability() throws java.sql.SQLException {
        return delegate.getHoldability();
    }

    @Override
    public java.sql.DatabaseMetaData getMetaData() throws java.sql.SQLException {
        return delegate.getMetaData();
    }

    @Override
    public int getNetworkTimeout() throws java.sql.SQLException {
        return delegate.getNetworkTimeout();
    }

    @Override
    public java.lang.String getSchema() throws java.sql.SQLException {
        return delegate.getSchema();
    }

    @Override
    public int getTransactionIsolation() throws java.sql.SQLException {
        return delegate.getTransactionIsolation();
    }

    @Override
    public java.util.Map<java.lang.String, java.lang.Class<?>> getTypeMap() throws java.sql.SQLException {
        return delegate.getTypeMap();
    }

    @Override
    public java.sql.SQLWarning getWarnings() throws java.sql.SQLException {
        return delegate.getWarnings();
    }

    @Override
    public boolean isClosed() throws java.sql.SQLException {
        return delegate.isClosed();
    }

    @Override
    public boolean isReadOnly() throws java.sql.SQLException {
        return delegate.isReadOnly();
    }

    @Override
    public boolean isValid(int p0) throws java.sql.SQLException {
        return delegate.isValid(p0);
    }

    @Override
    public boolean isWrapperFor(java.lang.Class<?> p0) throws java.sql.SQLException {
        return p0.isInstance(delegate) || delegate.isWrapperFor(p0);
    }

    @Override
    public java.lang.String nativeSQL(java.lang.String p0) throws java.sql.SQLException {
        return delegate.nativeSQL(p0);
    }

    @Override
    public java.sql.CallableStatement prepareCall(java.lang.String p0, int p1, int p2, int p3) throws java.sql.SQLException {
        return new DelegatingCallableStatement(delegate.prepareCall(p0, p1, p2, p3), execution, this, p0);
    }

    @Override
    public java.sql.CallableStatement prepareCall(java.lang.String p0, int p1, int p2) throws java.sql.SQLException {
        return new DelegatingCallableStatement(delegate.prepareCall(p0, p1, p2), execution, this, p0);
    }

    @Override
    public java.sql.CallableStatement prepareCall(java.lang.String p0) throws java.sql.SQLException {
        return new DelegatingCallableStatement(delegate.prepareCall(p0), execution, this, p0);
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String p0, int[] p1) throws java.sql.SQLException {
        return new DelegatingPreparedStatement(delegate.prepareStatement(p0, p1), execution, this, p0);
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String p0, java.lang.String[] p1) throws java.sql.SQLException {
        return new DelegatingPreparedStatement(delegate.prepareStatement(p0, p1), execution, this, p0);
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String p0, int p1, int p2, int p3) throws java.sql.SQLException {
        return new DelegatingPreparedStatement(delegate.prepareStatement(p0, p1, p2, p3), execution, this, p0);
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String p0, int p1, int p2) throws java.sql.SQLException {
        return new DelegatingPreparedStatement(delegate.prepareStatement(p0, p1, p2), execution, this, p0);
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String p0, int p1) throws java.sql.SQLException {
        return new DelegatingPreparedStatement(delegate.prepareStatement(p0, p1), execution, this, p0);
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String p0) throws java.sql.SQLException {
        return new DelegatingPreparedStatement(delegate.prepareStatement(p0), execution, this, p0);
    }

    @Override
    public void releaseSavepoint(java.sql.Savepoint p0) throws java.sql.SQLException {
        delegate.releaseSavepoint(p0);
    }

    @Override
    public void rollback() throws java.sql.SQLException {
        delegate.rollback();
    }

    @Override
    public void rollback(java.sql.Savepoint p0) throws java.sql.SQLException {
        delegate.rollback(p0);
    }

    @Override
    public void setAutoCommit(boolean p0) throws java.sql.SQLException {
        delegate.setAutoCommit(p0);
    }

    @Override
    public void setCatalog(java.lang.String p0) throws java.sql.SQLException {
        delegate.setCatalog(p0);
    }

    @Override
    public void setClientInfo(java.lang.String p0, java.lang.String p1) throws java.sql.SQLClientInfoException {
        delegate.setClientInfo(p0, p1);
    }

    @Override
    public void setClientInfo(java.util.Properties p0) throws java.sql.SQLClientInfoException {
        delegate.setClientInfo(p0);
    }

    @Override
    public void setHoldability(int p0) throws java.sql.SQLException {
        delegate.setHoldability(p0);
    }

    @Override
    public void setNetworkTimeout(java.util.concurrent.Executor p0, int p1) throws java.sql.SQLException {
        delegate.setNetworkTimeout(p0, p1);
    }

    @Override
    public void setReadOnly(boolean p0) throws java.sql.SQLException {
        delegate.setReadOnly(p0);
    }

    @Override
    public java.sql.Savepoint setSavepoint() throws java.sql.SQLException {
        return delegate.setSavepoint();
    }

    @Override
    public java.sql.Savepoint setSavepoint(java.lang.String p0) throws java.sql.SQLException {
        return delegate.setSavepoint(p0);
    }

    @Override
    public void setSchema(java.lang.String p0) throws java.sql.SQLException {
        delegate.setSchema(p0);
    }

    @Override
    public void setShardingKey(java.sql.ShardingKey p0, java.sql.ShardingKey p1) throws java.sql.SQLException {
        delegate.setShardingKey(p0, p1);
    }

    @Override
    public void setShardingKey(java.sql.ShardingKey p0) throws java.sql.SQLException {
        delegate.setShardingKey(p0);
    }

    @Override
    public boolean setShardingKeyIfValid(java.sql.ShardingKey p0, int p1) throws java.sql.SQLException {
        return delegate.setShardingKeyIfValid(p0, p1);
    }

    @Override
    public boolean setShardingKeyIfValid(java.sql.ShardingKey p0, java.sql.ShardingKey p1, int p2) throws java.sql.SQLException {
        return delegate.setShardingKeyIfValid(p0, p1, p2);
    }

    @Override
    public void setTransactionIsolation(int p0) throws java.sql.SQLException {
        delegate.setTransactionIsolation(p0);
    }

    @Override
    public void setTypeMap(java.util.Map<java.lang.String, java.lang.Class<?>> p0) throws java.sql.SQLException {
        delegate.setTypeMap(p0);
    }

    @Override
    public <T> T unwrap(java.lang.Class<T> p0) throws java.sql.SQLException {
        return p0.isInstance(delegate) ? p0.cast(delegate) : delegate.unwrap(p0);
    }
}
