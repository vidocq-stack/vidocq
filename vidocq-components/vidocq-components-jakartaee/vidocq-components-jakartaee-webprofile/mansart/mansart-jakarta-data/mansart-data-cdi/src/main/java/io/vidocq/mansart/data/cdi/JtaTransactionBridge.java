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
package io.vidocq.mansart.data.cdi;

import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.core.TransactionBridge;
import io.vidocq.mansart.transactions.jdbc.ConnectionXAResource;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * JTA implementation of {@link TransactionBridge} (MANSART-007): inside an active transaction,
 * every datasource used by a repository gets ONE connection, enlisted in the transaction —
 * the transaction outcome then governs the writes; the connection is closed after completion.
 *
 * <p>Enlistment is XA-first: when the datasource is XA-capable ({@code instanceof XADataSource}
 * or unwrappable to one — H2's {@code JdbcDataSource} and pool wrappers that delegate
 * {@code unwrap}), an {@link XAConnection} is opened and the <b>driver's</b> {@code XAResource}
 * is enlisted — real prepare, recoverable in-doubt branches. Otherwise a plain connection is
 * wrapped in {@link ConnectionXAResource} (single-phase, LRCO): the coordinator commits it
 * first and rolls the prepared XA branches back if it fails. One plain resource per transaction
 * is therefore safe; a WARN is logged when a second one joins the same transaction.
 *
 * <p>State lives in the {@link TransactionSynchronizationRegistry} (already scoped to the
 * active transaction), keyed by datasource identity.
 */
final class JtaTransactionBridge implements TransactionBridge {

    private static final System.Logger LOG = System.getLogger(JtaTransactionBridge.class.getName());

    /** TSR key counting plain (single-phase) enlistments in the current transaction. */
    private static final Object LOCAL_COUNT_KEY = new Object() {
        @Override public String toString() { return "JtaTransactionBridge.localCount"; }
    };

    private final TransactionManager tm;
    private final TransactionSynchronizationRegistry tsr;

    JtaTransactionBridge(TransactionManager tm, TransactionSynchronizationRegistry tsr) {
        this.tm = tm;
        this.tsr = tsr;
    }

    /** Identity key: datasource beans are singletons, two keys match iff same instance. */
    private record Key(DataSource dataSource) {
        @Override public boolean equals(Object o) {
            return o instanceof Key k && k.dataSource == dataSource;
        }
        @Override public int hashCode() {
            return System.identityHashCode(dataSource);
        }
    }

    private record Enlisted(Connection connection, AutoCloseable closer) {}

    @Override
    public Connection connectionFor(DataSource dataSource) {
        Transaction tx = activeTransaction();
        if (tx == null) return null;

        Key key = new Key(dataSource);
        Enlisted existing = (Enlisted) tsr.getResource(key);
        if (existing != null) return existing.connection();

        try {
            XADataSource xaDs = xaCapable(dataSource);
            Enlisted enlisted = xaDs != null ? enlistXa(tx, xaDs) : enlistPlain(tx, dataSource);
            tsr.putResource(key, enlisted);
            tsr.registerInterposedSynchronization(new Synchronization() {
                @Override public void beforeCompletion() { /* no-op */ }
                @Override public void afterCompletion(int status) {
                    try {
                        enlisted.closer().close();
                    } catch (Exception ignored) {
                        // Best effort — the TM already committed/rolled back through the XAResource.
                    }
                }
            });
            return enlisted.connection();
        } catch (SQLException | jakarta.transaction.RollbackException
                 | jakarta.transaction.SystemException e) {
            throw new MansartDataException(
                    "Failed to enlist a connection in the active transaction", e);
        }
    }

    private Transaction activeTransaction() {
        try {
            Transaction tx = tm.getTransaction();
            if (tx == null) return null;
            int status = tx.getStatus();
            return (status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK)
                    ? tx : null;
        } catch (jakarta.transaction.SystemException e) {
            throw new MansartDataException("Cannot read the transaction status", e);
        }
    }

    private static XADataSource xaCapable(DataSource ds) {
        if (ds instanceof XADataSource xa) return xa;
        try {
            if (ds.isWrapperFor(XADataSource.class)) return ds.unwrap(XADataSource.class);
        } catch (SQLException notAWrapper) {
            // JDBC allows isWrapperFor/unwrap to throw — treat as not XA-capable.
        }
        return null;
    }

    private Enlisted enlistXa(Transaction tx, XADataSource xaDs)
            throws SQLException, jakarta.transaction.RollbackException, jakarta.transaction.SystemException {
        XAConnection xaConnection = xaDs.getXAConnection();
        try {
            tx.enlistResource(xaConnection.getXAResource());
        } catch (RuntimeException | jakarta.transaction.RollbackException
                 | jakarta.transaction.SystemException e) {
            try { xaConnection.close(); } catch (SQLException ignored) { /* best effort */ }
            throw e;
        }
        return new Enlisted(xaConnection.getConnection(), xaConnection::close);
    }

    private Enlisted enlistPlain(Transaction tx, DataSource ds)
            throws SQLException, jakarta.transaction.RollbackException, jakarta.transaction.SystemException {
        Connection connection = ds.getConnection();
        try {
            tx.enlistResource(new ConnectionXAResource(connection));
        } catch (RuntimeException | jakarta.transaction.RollbackException
                 | jakarta.transaction.SystemException e) {
            try { connection.close(); } catch (SQLException ignored) { /* best effort */ }
            throw e;
        }
        Integer count = (Integer) tsr.getResource(LOCAL_COUNT_KEY);
        int localCount = count == null ? 1 : count + 1;
        tsr.putResource(LOCAL_COUNT_KEY, localCount);
        if (localCount == 2) {
            LOG.log(System.Logger.Level.WARNING,
                    "Two non-XA datasources enlisted in the same transaction — their commits are"
                            + " last-resource best-effort (a failure between them cannot be rolled"
                            + " back). Use XA-capable datasources for atomic multi-store commits.");
        }
        return new Enlisted(connection, connection::close);
    }
}
