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
package io.vidocq.mansart.pool;

import java.time.Duration;

/**
 * Immutable snapshot of pool counters. Implementations expose live data via a separate
 * {@code snapshot()} call (defined in {@code mansart-pool-core}); this contract describes
 * what every snapshot must carry, not how it is gathered.
 *
 * <p>Sealed so consumers can {@code switch} exhaustively when more snapshot variants
 * (per-tenant, per-shard) are introduced.
 */
public sealed interface PoolMetrics permits PoolMetrics.Snapshot {

    /** Connections currently lent out. */
    int active();

    /** Connections held in the idle deque. */
    int idle();

    /** Threads currently waiting for a permit. */
    int waiting();

    /** Total successful borrows since pool start. */
    long totalBorrows();

    /** Total {@link PoolException.Reason#ACQUIRE_TIMEOUT}s since pool start. */
    long totalTimeouts();

    /** Mean borrow latency since pool start (full lifetime, not a window). */
    Duration meanBorrowDuration();

    /**
     * Total number of leak warnings emitted (one per leaked connection, not per sweep). Always
     * zero when {@link PoolConfig#leakDetectionThreshold()} is disabled.
     */
    long totalLeaks();

    /**
     * Plain-record snapshot. The pool produces fresh instances on each {@code snapshot()} call —
     * never mutate the returned values. {@code permits} this single shape for v1; richer variants
     * (per-tenant, time-windowed) can be added as further {@code permits} subtypes.
     */
    record Snapshot(int active, int idle, int waiting,
                    long totalBorrows, long totalTimeouts, long totalLeaks,
                    Duration meanBorrowDuration) implements PoolMetrics {}
}
