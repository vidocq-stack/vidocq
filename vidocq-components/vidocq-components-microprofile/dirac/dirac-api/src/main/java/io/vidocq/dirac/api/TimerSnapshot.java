package io.vidocq.dirac.api;

import java.time.Duration;

/**
 * Snapshot immuable d'un timer Dirac.
 */
public record TimerSnapshot(
        long count,
        Duration elapsedTime,
        HistogramSnapshot histogram
) {
}

