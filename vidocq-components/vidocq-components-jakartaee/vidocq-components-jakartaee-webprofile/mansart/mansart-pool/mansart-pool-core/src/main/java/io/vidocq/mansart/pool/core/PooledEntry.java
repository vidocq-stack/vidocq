package io.vidocq.mansart.pool.core;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The pool-internal handle on a driver {@link Connection}: tracks its lifetime, gets parked in the
 * idle deque, and is wrapped in a fresh {@link PooledConnection} on every borrow.
 *
 * <p>Why a separate type? A wrapper recycled across borrows would have to mutate its own
 * "user has called close()" flag — and a stale {@code Connection} reference held by the previous
 * borrower would suddenly look "open" again after a re-borrow. A new {@link PooledConnection}
 * per borrow keeps the lifecycle rule simple: <i>close-then-reuse-the-same-handle</i> is always
 * a misuse, observable via {@code isClosed()}.
 */
final class PooledEntry {

    final Connection delegate;
    final long       createdAtNanos;
    volatile long    lastUsedAtNanos;

    PooledEntry(Connection delegate) {
        this.delegate        = delegate;
        this.createdAtNanos  = System.nanoTime();
        this.lastUsedAtNanos = this.createdAtNanos;
    }

    /** Reset driver state to a sane default before parking the entry back into the idle deque. */
    void resetForReuse() throws SQLException {
        if (!delegate.getAutoCommit()) {
            delegate.rollback();
            delegate.setAutoCommit(true);
        }
        delegate.clearWarnings();
        lastUsedAtNanos = System.nanoTime();
    }

    void closeUnderlying() {
        try { delegate.close(); } catch (SQLException ignored) { /* best-effort */ }
    }

    boolean expired(long maxLifetimeNanos, long idleTimeoutNanos, long nowNanos) {
        if (nowNanos - createdAtNanos  > maxLifetimeNanos) return true;
        return nowNanos - lastUsedAtNanos > idleTimeoutNanos;
    }
}
