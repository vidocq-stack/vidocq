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

import io.vidocq.mansart.transactions.jdbc.ConnectionXAResource;
import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A pool whose connections take part in the current JTA transaction: the data source a JTA persistence unit needs
 * from its container (Jakarta Persistence 3.2, §7.6 and §8.2.1.5).
 *
 * <p>Within a transaction, the first call takes a connection from the pool and enlists it in the transaction manager
 * ({@link ConnectionXAResource}, a single-phase resource); every later call of the same transaction shares it, through
 * a handle that leaves its completion to the manager. The connection goes back to the pool once the transaction has
 * ended. Outside a transaction, a call is the pool's own.
 *
 * <p>A transaction runs on one thread at a time (JTA 2.0, §3.2.1): two calls of the same transaction never race.
 */
final class EnlistingDataSource implements DataSource {

    private final DataSource pool;
    private final TransactionManager manager;
    private final Map<Transaction, Connection> enlisted = new ConcurrentHashMap<>();

    EnlistingDataSource(DataSource pool, TransactionManager manager) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override
    public Connection getConnection() throws SQLException {
        Transaction transaction = activeTransaction();
        if (transaction == null) {
            return pool.getConnection();
        }
        Connection physical = enlisted.get(transaction);
        return new TransactionConnection(physical != null ? physical : enlist(transaction));
    }

    /** The pool's credentials are its own: the user and password of the call are not used. */
    @Override
    public Connection getConnection(String user, String password) throws SQLException {
        return getConnection();
    }

    private Transaction activeTransaction() throws SQLException {
        try {
            Transaction transaction = manager.getTransaction();
            if (transaction == null) {
                return null;
            }
            int status = transaction.getStatus();
            return status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK ? transaction : null;
        } catch (SystemException e) {
            throw new SQLException("Unable to read the status of the current transaction", e);
        }
    }

    private Connection enlist(Transaction transaction) throws SQLException {
        Connection physical = pool.getConnection();
        try {
            if (!transaction.enlistResource(new ConnectionXAResource(physical))) {
                throw new SQLException("The transaction manager refused to enlist the connection");
            }
            transaction.registerSynchronization(new Release(transaction, physical));
        } catch (SQLException e) {
            closeQuietly(physical, e);
            throw e;
        } catch (RollbackException | SystemException | RuntimeException e) {
            SQLException failure = new SQLException("Unable to enlist a connection in the current transaction", e);
            closeQuietly(physical, failure);
            throw failure;
        }
        enlisted.put(transaction, physical);
        return physical;
    }

    private static void closeQuietly(Connection physical, SQLException failure) {
        try {
            physical.close();
        } catch (SQLException e) {
            failure.addSuppressed(e);
        }
    }

    /** Hands the connection of a transaction back to the pool once the manager has completed it. */
    private final class Release implements Synchronization {
        private final Transaction transaction;
        private final Connection physical;

        Release(Transaction transaction, Connection physical) {
            this.transaction = transaction;
            this.physical = physical;
        }

        @Override
        public void beforeCompletion() {
            // The manager completes the connection through its resource; nothing to do before.
        }

        @Override
        public void afterCompletion(int status) {
            enlisted.remove(transaction, physical);
            try {
                physical.close();
            } catch (SQLException e) {
                System.getLogger(EnlistingDataSource.class.getName()).log(System.Logger.Level.WARNING,
                        "Unable to hand the connection of a completed transaction back to the pool", e);
            }
        }
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return pool.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { pool.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { pool.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return pool.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return pool.getParentLogger(); }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        return type.isInstance(this) ? type.cast(this) : pool.unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this) || pool.isWrapperFor(type);
    }
}
