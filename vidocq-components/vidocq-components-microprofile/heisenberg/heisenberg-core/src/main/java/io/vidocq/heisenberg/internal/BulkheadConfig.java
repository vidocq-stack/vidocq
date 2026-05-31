package io.vidocq.heisenberg.internal;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Immutable configuration for the {@code @Bulkhead} policy.
 *
 * <p>MP FT 4.1 §7: synchronous and asynchronous bulkhead.</p>
 *
 * @param value              maximum capacity (default 10)
 * @param waitingTaskQueue   async-mode wait queue size (default 10)
 */
public record BulkheadConfig(
        int value,
        int waitingTaskQueue
) {

    /** Default constants (MP FT 4.1 spec §7). */
    public static final BulkheadConfig DEFAULT = new BulkheadConfig(10, 10);

    public BulkheadConfig {
        if (value <= 0) {
            throw new IllegalArgumentException("value (max concurrent) must be > 0");
        }
        if (waitingTaskQueue < 0) {
            throw new IllegalArgumentException("waitingTaskQueue must be >= 0");
        }
    }
}

