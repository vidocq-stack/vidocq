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

import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The connection of the current JTA transaction for a data source: one per transaction and per data source, whoever
 * asks for it (MANSART-066).
 *
 * <p>The first call in a transaction takes a connection from the data source and enlists it; every later call for
 * the same data source in the same transaction answers that connection, and it is closed (given back to its pool)
 * once the transaction has completed. The connection lives in the {@link TransactionSynchronizationRegistry}, keyed
 * by the identity of the data source: two instances of this class over the same registry, a repository bridge and a
 * {@link JtaDataSource} for example, share it. A {@link JtaDataSource} stands for the data source it wraps.
 *
 * <p>Enlistment is XA-first: when the data source is XA-capable ({@code instanceof XADataSource} or unwrappable to
 * one), an {@link XAConnection} is taken and the <b>driver's</b> {@code XAResource} is enlisted (real prepare,
 * recoverable in-doubt branches). Otherwise a plain connection is wrapped in a {@link ConnectionXAResource}
 * (single-phase, last resource): one such resource per transaction is safe, and a warning is logged when a second
 * one joins.
 *
 * <p>A transaction runs on one thread at a time (JTA 2.0, §3.2.1): two calls of the same transaction never race.
 */
public final class JtaConnections {

    private static final System.Logger LOG = System.getLogger(JtaConnections.class.getName());

    /** Registry key counting the single-phase enlistments of the current transaction. */
    private static final Object LOCAL_COUNT_KEY = new Object() {
        @Override public String toString() { return "JtaConnections.localCount"; }
    };

    private final TransactionManager manager;
    private final TransactionSynchronizationRegistry registry;

    public JtaConnections(TransactionManager manager, TransactionSynchronizationRegistry registry) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Identity key: two keys match when they hold the same data source instance. */
    private record Key(DataSource dataSource) {
        @Override public boolean equals(Object other) {
            return other instanceof Key(var otherSource) && otherSource == dataSource;
        }
        @Override public int hashCode() {
            return System.identityHashCode(dataSource);
        }
    }

    /** The connection of a transaction, with what gives it back and whether the transaction is over. */
    record Enlisted(Connection connection, AutoCloseable closer, AtomicBoolean completed) {}

    /**
     * The connection of the current transaction for {@code dataSource}, enlisted on the first call.
     *
     * <p>The caller does not close it and leaves its completion to the transaction manager.
     *
     * @return the connection, or {@code null} when no transaction is active on the calling thread
     * @throws SQLException when the connection cannot be taken or enlisted
     */
    public Connection enlisted(DataSource dataSource) throws SQLException {
        Enlisted enlisted = enlistment(dataSource);
        return enlisted == null ? null : enlisted.connection();
    }

    /** Whether a transaction that can still do work is active on the calling thread. */
    boolean inTransaction() throws SQLException {
        return activeTransaction() != null;
    }

    Enlisted enlistment(DataSource dataSource) throws SQLException {
        Transaction transaction = activeTransaction();
        if (transaction == null) return null;

        DataSource source = dataSource instanceof JtaDataSource jta ? jta.pool() : dataSource;
        Key key = new Key(source);
        Enlisted existing = (Enlisted) registry.getResource(key);
        if (existing != null) return existing;

        try {
            XADataSource xaSource = xaCapable(source);
            Enlisted enlisted = xaSource != null ? enlistXa(transaction, xaSource) : enlistPlain(transaction, source);
            registry.putResource(key, enlisted);
            registry.registerInterposedSynchronization(new Release(enlisted));
            return enlisted;
        } catch (RollbackException | SystemException e) {
            throw new SQLException("Unable to enlist a connection in the current transaction", e);
        }
    }

    /** Gives the connection of a transaction back once the manager has completed it. */
    private record Release(Enlisted enlisted) implements Synchronization {
        @Override
        public void beforeCompletion() {
            // The manager completes the connection through its resource; nothing to do before.
        }

        @Override
        public void afterCompletion(int status) {
            enlisted.completed().set(true);
            try {
                enlisted.closer().close();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Unable to give back the connection of a completed transaction", e);
            }
        }
    }

    private Transaction activeTransaction() throws SQLException {
        try {
            Transaction transaction = manager.getTransaction();
            if (transaction == null) return null;
            int status = transaction.getStatus();
            return status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK ? transaction : null;
        } catch (SystemException e) {
            throw new SQLException("Unable to read the status of the current transaction", e);
        }
    }

    private static XADataSource xaCapable(DataSource dataSource) {
        if (dataSource instanceof XADataSource xa) return xa;
        try {
            if (dataSource.isWrapperFor(XADataSource.class)) return dataSource.unwrap(XADataSource.class);
        } catch (SQLException _) {
            // JDBC allows isWrapperFor/unwrap to throw: the data source is then taken as not XA-capable.
        }
        return null;
    }

    private static Enlisted enlistXa(Transaction transaction, XADataSource xaSource)
            throws SQLException, RollbackException, SystemException {
        XAConnection xaConnection = xaSource.getXAConnection();
        try {
            transaction.enlistResource(xaConnection.getXAResource());
            return new Enlisted(xaConnection.getConnection(), xaConnection::close, new AtomicBoolean());
        } catch (RuntimeException | SQLException | RollbackException | SystemException e) {
            closeAfterFailure(xaConnection::close, e);
            throw e;
        }
    }

    private Enlisted enlistPlain(Transaction transaction, DataSource dataSource)
            throws SQLException, RollbackException, SystemException {
        Connection connection = dataSource.getConnection();
        try {
            transaction.enlistResource(new ConnectionXAResource(connection));
        } catch (RuntimeException | RollbackException | SystemException e) {
            closeAfterFailure(connection, e);
            throw e;
        }
        Integer count = (Integer) registry.getResource(LOCAL_COUNT_KEY);
        int localCount = count == null ? 1 : count + 1;
        registry.putResource(LOCAL_COUNT_KEY, localCount);
        if (localCount == 2) {
            LOG.log(System.Logger.Level.WARNING,
                    "Two non-XA data sources enlisted in the same transaction: their commits are last-resource"
                            + " best-effort (a failure between them cannot be rolled back). Use XA-capable data"
                            + " sources for atomic multi-store commits.");
        }
        return new Enlisted(connection, connection, new AtomicBoolean());
    }

    private static void closeAfterFailure(AutoCloseable connection, Exception failure) {
        try {
            connection.close();
        } catch (Exception e) {
            failure.addSuppressed(e);
        }
    }
}
