package io.vidocq.heisenberg.internal;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Configuration immuable pour la politique {@code @Bulkhead}.
 *
 * <p>MP FT 4.1 §7 : bulkhead synchrone et asynchrone.</p>
 *
 * @param value              capacité max (default 10)
 * @param waitingTaskQueue   taille file attente mode async (default 10)
 */
public record BulkheadConfig(
        int value,
        int waitingTaskQueue
) {

    /** Constantes défaut (spec MP FT 4.1 §7). */
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

