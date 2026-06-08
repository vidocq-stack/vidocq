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

    /** {@code 0} when not currently borrowed; {@code System.nanoTime()} captured at acquire otherwise. */
    volatile long       borrowedAtNanos;
    /**
     * Stack snapshot of the last successful borrow when leak detection is enabled. {@code null}
     * when leak detection is disabled — keeps the cost of {@code new Throwable()} off the hot path.
     */
    volatile Throwable  borrowStack;
    /** Set once per borrow when a leak warning has been emitted, so the housekeeper does not spam. */
    volatile boolean    leakWarned;

    PooledEntry(Connection delegate) {
        this.delegate        = delegate;
        this.createdAtNanos  = System.nanoTime();
        this.lastUsedAtNanos = this.createdAtNanos;
    }

    /** Called from {@link ConnectionPool#takeOut} on every successful borrow. */
    void markBorrowed(long nowNanos, boolean captureStack) {
        this.borrowedAtNanos = nowNanos;
        this.borrowStack     = captureStack ? new Throwable("borrow trace") : null;
        this.leakWarned      = false;
    }

    /** Called from {@link ConnectionPool#release} so the next housekeeper sweep does not flag this entry. */
    void markIdle() {
        this.borrowedAtNanos = 0L;
        this.borrowStack     = null;
        this.leakWarned      = false;
    }

    boolean leaked(long thresholdNanos, long nowNanos) {
        long borrowedAt = borrowedAtNanos;
        return borrowedAt != 0L && nowNanos - borrowedAt > thresholdNanos;
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
