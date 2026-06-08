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

import java.sql.Connection;
import java.sql.DriverManager;
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
    private final boolean                          leakDetectionEnabled;
    private final long                             leakDetectionThresholdNanos;

    private final AtomicLong totalBorrows           = new AtomicLong();
    private final AtomicLong totalTimeouts          = new AtomicLong();
    private final AtomicLong totalLeaks             = new AtomicLong();
    private final AtomicLong sumBorrowDurationNanos = new AtomicLong();

    private volatile boolean closed;

    ConnectionPool(PoolConfig config) {
        this.config       = config;
        this.permits      = new Semaphore(config.maxSize(), false);
        this.driverProps  = buildDriverProps(config);
        this.leakDetectionEnabled = !config.leakDetectionThreshold().isZero();
        this.leakDetectionThresholdNanos = config.leakDetectionThreshold().toNanos();
        this.housekeeper  = new Housekeeper(this, config);

        // Pre-fill minIdle. Best-effort — if the driver is briefly unavailable, the pool still
        // boots and the housekeeper / borrow path will warm up later.
        for (int i = 0; i < config.minIdle(); i++) {
            try {
                idle.offerFirst(open());
            } catch (SQLException e) {
                break;
            }
        }
        housekeeper.start();
    }

    /* ---- borrow / release ---- */

    PooledConnection acquire() throws SQLException {
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

    void release(PooledEntry entry) {
        boolean keep;
        try {
            entry.resetForReuse();
            keep = !closed && !entry.expired(
                    config.maxLifetime().toNanos(),
                    config.idleTimeout().toNanos(),
                    System.nanoTime());
        } catch (SQLException e) {
            keep = false;        // reset failed → discard
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

    private PooledConnection takeOut(PooledEntry entry, long startNanos) {
        long now = System.nanoTime();
        inUse.add(entry);
        entry.markBorrowed(now, leakDetectionEnabled);
        totalBorrows.incrementAndGet();
        sumBorrowDurationNanos.addAndGet(now - startNanos);
        return new PooledConnection(entry, this);
    }

    /**
     * Called by the {@link Housekeeper} when leak detection is enabled. Walks {@code inUse} and
     * logs a single WARNING per leaked entry (gated by {@link PooledEntry#leakWarned}) — repeated
     * sweeps over the same leaker do not multiply log lines.
     */
    void detectLeaks(long nowNanos) {
        if (!leakDetectionEnabled) return;
        for (PooledEntry e : inUse) {
            if (e.leakWarned)                                                continue;
            if (!e.leaked(leakDetectionThresholdNanos, nowNanos))             continue;
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
        if (config.validation() == ValidationMode.ON_BORROW) {
            try {
                int timeoutSec = (int) Math.max(1, config.validationTimeout().toSeconds());
                return entry.delegate.isValid(timeoutSec);
            } catch (SQLException e) {
                return false;
            }
        }
        return true;
    }

    private PooledEntry open() throws SQLException {
        try {
            Connection raw = DriverManager.getConnection(config.jdbcUrl(), driverProps);
            return new PooledEntry(raw);
        } catch (SQLException e) {
            throw new PoolException(PoolException.Reason.CONNECT_FAILED,
                    "failed to open new connection: " + e.getMessage(), e);
        }
    }

    /** Called by the {@link Housekeeper} on its own virtual thread. */
    void sweep(long nowNanos) {
        long maxLife = config.maxLifetime().toNanos();
        long maxIdle = config.idleTimeout().toNanos();
        Iterator<PooledEntry> it = idle.iterator();
        while (it.hasNext()) {
            PooledEntry e = it.next();
            if (e.expired(maxLife, maxIdle, nowNanos) && idle.remove(e)) {
                e.closeUnderlying();
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
