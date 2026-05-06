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

    private final PoolConfig                       config;
    private final Properties                       driverProps;
    private final Semaphore                        permits;
    private final ConcurrentLinkedDeque<PooledEntry> idle  = new ConcurrentLinkedDeque<>();
    private final Set<PooledEntry>                 inUse = ConcurrentHashMap.newKeySet();
    private final Housekeeper                      housekeeper;

    private final AtomicLong totalBorrows           = new AtomicLong();
    private final AtomicLong totalTimeouts          = new AtomicLong();
    private final AtomicLong sumBorrowDurationNanos = new AtomicLong();

    private volatile boolean closed;

    ConnectionPool(PoolConfig config) {
        this.config       = config;
        this.permits      = new Semaphore(config.maxSize(), false);
        this.driverProps  = buildDriverProps(config);
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
        if (keep) {
            idle.offerFirst(entry);
        } else {
            entry.closeUnderlying();
        }
        permits.release();
    }

    /* ---- helpers ---- */

    private PooledConnection takeOut(PooledEntry entry, long startNanos) {
        inUse.add(entry);
        totalBorrows.incrementAndGet();
        sumBorrowDurationNanos.addAndGet(System.nanoTime() - startNanos);
        return new PooledConnection(entry, this);
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
                borrows, totalTimeouts.get(), mean);
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
