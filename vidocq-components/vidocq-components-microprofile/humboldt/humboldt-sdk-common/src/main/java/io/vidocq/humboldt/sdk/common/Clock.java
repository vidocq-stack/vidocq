package io.vidocq.humboldt.sdk.common;

import java.time.Instant;

/**
 * Time source used by the Humboldt SDK to timestamp spans, metrics, and logs.
 *
 * <p>Two distinct clocks:</p>
 * <ul>
 *   <li>{@link #now()} — wall clock in nanoseconds since the UTC epoch,
 *       used for timestamps of published spans/metrics/logs;</li>
 *   <li>{@link #nanoTime()} — monotonic clock in nanoseconds, used to
 *       measure durations (immune to NTP time jumps).</li>
 * </ul>
 */
public interface Clock {

    /**
     * @return the default system clock.
     */
    static Clock system() {
        return SystemClock.INSTANCE;
    }

    /**
     * @return the current instant in nanoseconds since the UTC epoch.
     */
    long now();

    /**
     * @return a monotonic counter in nanoseconds, see {@link System#nanoTime()}.
     */
    long nanoTime();

    final class SystemClock implements Clock {

        static final SystemClock INSTANCE = new SystemClock();

        @Override
        public long now() {
            Instant n = Instant.now();
            return Math.multiplyExact(n.getEpochSecond(), 1_000_000_000L) + n.getNano();
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    }
}
