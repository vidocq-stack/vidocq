package io.vidocq.mansart.transactions.core;

import java.util.List;

/**
 * Default {@link RecoveryLog} — discards everything. Used when durability is not required (tests,
 * embedded apps that prefer "lose any in-doubt TX on crash" over the cost of fsync per commit).
 *
 * <p>{@link #scan()} always returns an empty list — there is nothing to recover when no journal
 * was kept.
 */
public final class NoOpRecoveryLog implements RecoveryLog {

    public static final NoOpRecoveryLog INSTANCE = new NoOpRecoveryLog();

    private NoOpRecoveryLog() {}

    @Override
    public void append(Record record) {
        // no-op
    }

    @Override
    public List<Record> scan() {
        return List.of();
    }

    @Override
    public void close() {
        // no-op
    }
}
