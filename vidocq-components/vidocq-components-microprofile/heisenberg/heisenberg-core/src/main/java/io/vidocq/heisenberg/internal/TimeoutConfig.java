package io.vidocq.heisenberg.internal;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Immutable configuration for the {@code @Timeout} policy.
 *
 * <p>MP FT 4.1 §4: the timeout applies to each individual attempt.</p>
 *
 * @param value timeout duration (must be &gt; 0)
 * @param unit  ChronoUnit unit (spec default: MILLIS)
 */
public record TimeoutConfig(long value, ChronoUnit unit) {

    /** Spec default value: 1,000 ms. */
    public static final TimeoutConfig DEFAULT = new TimeoutConfig(1_000L, ChronoUnit.MILLIS);

    public TimeoutConfig {
        if (value <= 0) {
            throw new IllegalArgumentException("timeout value must be > 0, got: " + value);
        }
    }

    /** Converts the configuration to {@link Duration}. */
    public Duration duration() {
        return Duration.of(value, unit);
    }
}

