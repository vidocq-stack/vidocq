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
package io.vidocq.mansart.transactions.jdbc;

import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * A data source whose connections take part in the current JTA transaction: what a container hands to a JTA
 * persistence unit, a repository or plain JDBC code (MANSART-066).
 *
 * <p>Within a transaction, every call answers a handle on the one connection {@link JtaConnections} enlisted for the
 * wrapped data source: the callers of a transaction see each other's work, and the transaction manager alone commits
 * or rolls back. Closing a handle leaves the connection to the transaction; the connection goes back to its pool
 * once the transaction has completed. Outside a transaction, a call is the wrapped data source's own.
 *
 * <p>{@code unwrap} answers this wrapper for its own type and asks the wrapped data source for any other, so an
 * XA-capable pool stays visible to a recovery scan.
 */
public final class JtaDataSource implements DataSource {

    private final DataSource pool;
    private final JtaConnections connections;

    public JtaDataSource(DataSource pool, TransactionManager manager, TransactionSynchronizationRegistry registry) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.connections = new JtaConnections(manager, registry);
    }

    /** The wrapped data source: the identity the connection of a transaction is kept under. */
    DataSource pool() {
        return pool;
    }

    @Override
    public Connection getConnection() throws SQLException {
        JtaConnections.Enlisted enlisted = connections.enlistment(pool);
        return enlisted == null ? pool.getConnection() : new EnlistedConnection(enlisted);
    }

    /**
     * Outside a transaction, the wrapped data source's own. Within one, refused: the connection of the transaction
     * is shared, and was opened with the credentials of the data source.
     */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        if (connections.inTransaction()) {
            throw new SQLFeatureNotSupportedException(
                    "The connection of a JTA transaction is shared: it cannot take the credentials of a call");
        }
        return pool.getConnection(username, password);
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        return type.isInstance(this) ? type.cast(this) : pool.unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this) || pool.isWrapperFor(type);
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return pool.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { pool.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { pool.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return pool.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return pool.getParentLogger(); }
}
