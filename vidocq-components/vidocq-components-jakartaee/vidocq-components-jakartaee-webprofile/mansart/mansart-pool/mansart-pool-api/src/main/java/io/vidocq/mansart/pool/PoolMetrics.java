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
