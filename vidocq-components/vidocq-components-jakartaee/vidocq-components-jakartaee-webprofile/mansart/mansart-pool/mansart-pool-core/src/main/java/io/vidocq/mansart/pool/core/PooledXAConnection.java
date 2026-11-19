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

import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;
import javax.sql.StatementEventListener;
import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One borrow of a pool built on an {@code XADataSource}, handed to a transaction manager (MANSART-065). It
 * holds a {@link PooledEntry} as a {@link PooledConnection} does, with the contract of {@link XAConnection}:
 * <ul>
 *   <li>{@link #getXAResource()} is the <b>driver's</b> resource for the physical connection — real prepare,
 *       branches the database can recover;</li>
 *   <li>{@link #getConnection()} is a logical handle. Closing it tells the listeners and keeps the borrow; asking
 *       for another one closes the one before, as JDBC says;</li>
 *   <li>{@link #close()} gives the physical connection back to the pool, where a driver's XA connection would
 *       close it. Still inside a transaction branch, the connection is closed instead of kept.</li>
 * </ul>
 * After {@code close()} the handle and this object no longer reach the physical connection (SQLSTATE 08003).
 */
final class PooledXAConnection implements XAConnection {

    private final PooledEntry    entry;
    private final ConnectionPool pool;
    private final AtomicBoolean  closed = new AtomicBoolean();
    private final AtomicReference<PooledConnection> handle = new AtomicReference<>();
    private final List<ConnectionEventListener> listeners = new CopyOnWriteArrayList<>();

    PooledXAConnection(PooledEntry entry, ConnectionPool pool) {
        this.entry = entry;
        this.pool  = pool;
    }

    @Override
    public XAResource getXAResource() throws SQLException {
        ensureOpen();
        return entry.xa.getXAResource();
    }

    @Override
    public Connection getConnection() throws SQLException {
        ensureOpen();
        PooledConnection fresh = new PooledConnection(entry, pool, this);
        PooledConnection previous = handle.getAndSet(fresh);
        if (previous != null) {
            previous.invalidate();
        }
        return fresh;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        PooledConnection last = handle.getAndSet(null);
        if (last != null) {
            last.invalidate();
        }
        pool.releaseXa(entry);
    }

    /** Called by the handle the application closed: the borrow goes on, its listeners are told. */
    void handleClosed() {
        ConnectionEvent event = new ConnectionEvent(this);
        for (ConnectionEventListener listener : listeners) {
            listener.connectionClosed(event);
        }
    }

    private void ensureOpen() throws SQLException {
        if (closed.get()) {
            throw new SQLException("This XA connection was closed: it went back to the pool", "08003");
        }
    }

    @Override
    public void addConnectionEventListener(ConnectionEventListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeConnectionEventListener(ConnectionEventListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void addStatementEventListener(StatementEventListener listener) {
        // The pool keeps no statement cache: there is no statement event to tell.
    }

    @Override
    public void removeStatementEventListener(StatementEventListener listener) {
        // Nothing was registered.
    }
}
