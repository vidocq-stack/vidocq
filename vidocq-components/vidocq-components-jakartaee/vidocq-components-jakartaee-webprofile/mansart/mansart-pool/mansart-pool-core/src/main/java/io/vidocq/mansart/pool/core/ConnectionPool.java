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

import io.vidocq.mansart.pool.PoolConfig;
import io.vidocq.mansart.pool.PoolException;
import io.vidocq.mansart.pool.PoolMetrics;
import io.vidocq.mansart.pool.ValidationMode;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pool engine — package-private; the public face is {@link MansartDataSource}.
 *
 * <p>Concurrency model:
 * <ul>
 *   <li>{@link Semaphore} caps the in-flight count at {@code maxSize}; the only blocking wait on
 *       the borrow path. Non-fair to favour throughput; uses {@code LockSupport.park} internally —
 *       no {@code synchronized}, no virtual-thread pinning.</li>
 *   <li>{@link ConcurrentLinkedDeque} holds idle entries in LIFO order so the hottest socket
 *       (warmest TCP, warmest JIT in the driver) is reused first.</li>
 *   <li>{@link ConcurrentHashMap}-backed {@link Set} tracks borrowed entries — feeds leak
 *       detection in MP-C.</li>
 * </ul>
 *
 * <p>The borrow path returns a fresh {@link PooledConnection} per call wrapping a {@link PooledEntry};
 * the entry — not the wrapper — is what cycles between {@code idle} and {@code inUse}.
 */
final class ConnectionPool implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("io.vidocq.mansart.pool");

    private final PoolConfig                       config;
    private final Properties                       driverProps;
    private final Semaphore                        permits;
    private final ConcurrentLinkedDeque<PooledEntry> idle  = new ConcurrentLinkedDeque<>();
    private final Set<PooledEntry>                 inUse = ConcurrentHashMap.newKeySet();
    private final Housekeeper                      housekeeper;
    /** The application's class loader when the pool was built: where its JDBC driver is looked up (mansart#42). */
    private final ClassLoader                      driverLoader;
    /** The JDBC driver, resolved at the first {@link #open()} and kept for the pool's lifetime. */
    private volatile Driver                        driver;
    private final boolean                          leakDetectionEnabled;
    private final long                             leakDetectionThresholdNanos;

    private final AtomicLong totalBorrows           = new AtomicLong();
    private final AtomicLong totalTimeouts          = new AtomicLong();
    private final AtomicLong totalLeaks             = new AtomicLong();
    private final AtomicLong sumBorrowDurationNanos = new AtomicLong();

    private volatile boolean closed;

    /** Where the pool gets the driver's {@code XADataSource}, built at its first use. */
    @FunctionalInterface
    interface XaSource {
        XADataSource get() throws SQLException;
    }

    /** {@code null} for a pool of plain connections; else every connection is opened on it (MANSART-065). */
    private final XaSource xaSource;

    ConnectionPool(PoolConfig config) {
        this(config, null);
    }

    ConnectionPool(PoolConfig config, XaSource xaSource) {
        this.config       = config;
        this.xaSource     = xaSource;
        this.permits      = new Semaphore(config.maxSize(), false);
        this.driverProps  = buildDriverProps(config);
        this.leakDetectionEnabled = !config.leakDetectionThreshold().isZero();
        this.leakDetectionThresholdNanos = config.leakDetectionThreshold().toNanos();
        this.driverLoader = DriverLocator.applicationLoader(ConnectionPool.class);
        this.housekeeper  = new Housekeeper(this, config);

        // Pre-fill minIdle. Best-effort — if the driver is briefly unavailable, the pool still
        // boots and the housekeeper / borrow path will warm up later.
        for (int i = 0; i < config.minIdle(); i++) {
            try {
                idle.offerFirst(open());
            } catch (SQLException _) {
                break;
            }
        }
        housekeeper.start();
    }

    /* ---- borrow / release ---- */

    PooledConnection acquire() throws SQLException {
        return new PooledConnection(borrow(), this);
    }

    /**
     * Borrows a connection for a transaction manager: the pool's {@code maxSize}, metrics, leak detection and
     * reuse apply to it as to any borrow (MANSART-065).
     */
    PooledXAConnection acquireXa() throws SQLException {
        return new PooledXAConnection(borrow(), this);
    }

    private PooledEntry borrow() throws SQLException {
        if (closed) {
            throw new PoolException(PoolException.Reason.POOL_CLOSED, "MansartDataSource is closed");
        }
        long startNanos = System.nanoTime();
        boolean acquired;
        try {
            acquired = permits.tryAcquire(config.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PoolException(PoolException.Reason.ACQUIRE_TIMEOUT,
                    "interrupted while waiting for a connection", e);
        }
        if (!acquired) {
            totalTimeouts.incrementAndGet();
            throw new PoolException(PoolException.Reason.ACQUIRE_TIMEOUT,
                    "no connection available within " + config.acquireTimeout());
        }

        try {
            PooledEntry entry;
            while ((entry = idle.pollFirst()) != null) {
                if (acceptForBorrow(entry)) {
                    return takeOut(entry, startNanos);
                }
                entry.closeUnderlying();
            }
            return takeOut(open(), startNanos);
        } catch (SQLException | RuntimeException e) {
            permits.release();
            throw e;
        }
    }

    void release(PooledEntry entry, boolean workPending) {
        release(entry, workPending, false);
    }

    /**
     * Gives back the connection of an XA lease. Out of auto-commit, it is still inside a transaction branch the
     * transaction manager did not finish: the pool closes it, which rolls the branch back, rather than hand a
     * connection in a branch to the next borrower.
     */
    void releaseXa(PooledEntry entry) {
        release(entry, false, true);
    }

    private void release(PooledEntry entry, boolean workPending, boolean xaLease) {
        boolean keep;
        try {
            keep = !(xaLease && !entry.delegate.getAutoCommit());
            if (keep) {
                entry.resetForReuse(workPending);
                keep = !closed && !entry.expired(
                        config.maxLifetime().toNanos(),
                        config.idleTimeout().toNanos(),
                        System.nanoTime());
            }
        } catch (SQLException | RuntimeException _) {
            // Reset failed → discard. A driver's RuntimeException too: the permit below must be
            // given back whatever the driver throws, or the pool shrinks for good (MANSART-057).
            keep = false;
        }
        inUse.remove(entry);
        entry.markIdle();
        if (keep) {
            idle.offerFirst(entry);
        } else {
            entry.closeUnderlying();
        }
        permits.release();
    }

    /* ---- helpers ---- */

    private PooledEntry takeOut(PooledEntry entry, long startNanos) {
        long now = System.nanoTime();
        inUse.add(entry);
        entry.markBorrowed(now, leakDetectionEnabled);
        totalBorrows.incrementAndGet();
        sumBorrowDurationNanos.addAndGet(now - startNanos);
        return entry;
    }

    /**
     * Called by the {@link Housekeeper} when leak detection is enabled. Walks {@code inUse} and
     * logs a single WARNING per leaked entry (gated by {@link PooledEntry#leakWarned}) — repeated
     * sweeps over the same leaker do not multiply log lines.
     */
    void detectLeaks(long nowNanos) {
        if (!leakDetectionEnabled) return;
        for (PooledEntry e : inUse) {
            if (e.leakWarned || !e.leaked(leakDetectionThresholdNanos, nowNanos)) continue;
            e.leakWarned = true;
            totalLeaks.incrementAndGet();
            LOG.log(System.Logger.Level.WARNING,
                    "Pool connection leak suspected: held for over "
                            + config.leakDetectionThreshold()
                            + ". Borrow stack:",
                    e.borrowStack);
        }
    }

    boolean leakDetectionEnabled() {
        return leakDetectionEnabled;
    }

    private boolean acceptForBorrow(PooledEntry entry) {
        long now = System.nanoTime();
        if (entry.expired(config.maxLifetime().toNanos(), config.idleTimeout().toNanos(), now)) {
            return false;
        }
        return config.validation() != ValidationMode.ON_BORROW || alive(entry);
    }

    /**
     * Whether the driver's connection still answers: the {@code validationQuery} when one is configured, else
     * {@link Connection#isValid(int)}, within {@code validationTimeout}. A driver that throws is a dead connection.
     */
    private boolean alive(PooledEntry entry) {
        int timeoutSec = (int) Math.max(1, config.validationTimeout().toSeconds());
        try {
            String query = config.validationQuery();
            if (query == null) {
                return entry.delegate.isValid(timeoutSec);
            }
            try (java.sql.Statement statement = entry.delegate.createStatement()) {
                statement.setQueryTimeout(timeoutSec);
                statement.execute(query);
                return true;
            }
        } catch (SQLException | RuntimeException _) {
            return false;
        }
    }

    private PooledEntry open() throws SQLException {
        try {
            if (xaSource != null) {
                return openXa();
            }
            Driver used = driver();
            Connection raw = used.connect(config.jdbcUrl(), driverProps);
            if (raw == null) {
                throw new SQLException("The JDBC driver " + used.getClass().getName() + " accepted "
                        + config.jdbcUrl() + " but opened no connection", "08001");
            }
            return new PooledEntry(raw);
        } catch (SQLException e) {
            throw new PoolException(PoolException.Reason.CONNECT_FAILED,
                    "failed to open new connection: " + e.getMessage(), e);
        }
    }

    private PooledEntry openXa() throws SQLException {
        XAConnection xaConnection = xaSource.get().getXAConnection();
        try {
            return new PooledEntry(xaConnection);
        } catch (SQLException | RuntimeException e) {
            try {
                xaConnection.close();
            } catch (SQLException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
    }

    /** The pool's JDBC driver, found once through the loader captured at construction. */
    private Driver driver() throws SQLException {
        Driver found = driver;
        if (found == null) {
            found = DriverLocator.find(config.jdbcUrl(), driverLoader);
            driver = found;
        }
        return found;
    }

    /**
     * Called by the {@link Housekeeper} on its own virtual thread: closes the idle connections past
     * {@code maxLifetime} or {@code idleTimeout}. The pool keeps {@code minIdle} connections through the idle
     * timeout: one of those is checked instead, and stays when it answers (MANSART-059).
     */
    void sweep(long nowNanos) {
        long maxLife = config.maxLifetime().toNanos();
        long maxIdle = config.idleTimeout().toNanos();
        Iterator<PooledEntry> it = idle.iterator();
        while (it.hasNext()) {
            PooledEntry e = it.next();
            if (!e.expired(maxLife, maxIdle, nowNanos) || !idle.remove(e)) continue;
            boolean keep = !e.pastLifetime(maxLife, nowNanos)
                    && idle.size() + inUse.size() < config.minIdle()
                    && alive(e);
            if (keep) {
                e.touch();
                idle.offerLast(e);
            } else {
                e.closeUnderlying();
            }
        }
    }

    /**
     * Called by the {@link Housekeeper} in {@link ValidationMode#PERIODIC} mode: checks every idle connection and
     * closes the dead ones, so that a connection the database dropped is not the next borrower's error
     * (MANSART-058). One connection at a time leaves the deque, from its cold end, and goes back to its hot end:
     * a full pass leaves the order as it was.
     */
    void validateIdle() {
        if (config.validation() != ValidationMode.PERIODIC) return;
        PooledEntry e;
        for (int remaining = idle.size(); remaining > 0 && !closed && (e = idle.pollLast()) != null; remaining--) {
            if (alive(e)) {
                idle.offerFirst(e);
            } else {
                e.closeUnderlying();
            }
        }
    }

    /** Called by the {@link Housekeeper}: opens connections until the pool holds {@code minIdle} (MANSART-059). */
    void refill() {
        while (!closed && idle.size() + inUse.size() < config.minIdle()) {
            try {
                idle.offerLast(open());
            } catch (SQLException e) {
                LOG.log(System.Logger.Level.DEBUG, "Pool could not open a connection to reach minIdle", e);
                return;
            }
        }
    }

    PoolMetrics snapshot() {
        long borrows = totalBorrows.get();
        Duration mean = borrows == 0
                ? Duration.ZERO
                : Duration.ofNanos(sumBorrowDurationNanos.get() / borrows);
        return new PoolMetrics.Snapshot(
                inUse.size(), idle.size(), permits.getQueueLength(),
                borrows, totalTimeouts.get(), totalLeaks.get(), mean);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        housekeeper.stop();
        PooledEntry e;
        while ((e = idle.pollFirst()) != null) {
            e.closeUnderlying();
        }
        // inUse: leakers will close themselves through PooledConnection.close() → release(),
        // which now finds closed==true and discards the entry instead of parking.
    }

    private static Properties buildDriverProps(PoolConfig config) {
        Properties p = new Properties();
        if (config.username() != null) p.setProperty("user",     config.username());
        if (config.password() != null) p.setProperty("password", config.password());
        config.driverProperties().forEach(p::setProperty);
        return p;
    }
}
