package io.vidocq.heisenberg.internal;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Immutable configuration for the {@code @CircuitBreaker} policy.
 *
 * <p>MP FT 4.1 §5: circuit breaker with a count-based sliding window.</p>
 *
 * @param requestVolumeThreshold minimum number of requests required to trigger ratio calculation (default 20)
 * @param failureRatio           failure-ratio threshold: failure_count / request_count (default 0.5)
 * @param delay                  delay before the OPEN → HALF_OPEN transition (default 5s)
 * @param delayUnit              delay unit (default SECONDS)
 * @param successThreshold       number of successes required in HALF_OPEN to close (default 1)
 * @param failOn                 exceptions that increment failures (default Throwable.class)
 * @param skipOn                 ignored exceptions (takes precedence over failOn)
 */
public record CircuitBreakerConfig(
        int requestVolumeThreshold,
        double failureRatio,
        long delay,
        ChronoUnit delayUnit,
        int successThreshold,
        Class<? extends Throwable>[] failOn,
        Class<? extends Throwable>[] skipOn
) {

    /** Default constants (MP FT 4.1 spec §5). */
    public static final CircuitBreakerConfig DEFAULT = new CircuitBreakerConfig(
            20, 0.5, 5, ChronoUnit.SECONDS, 1,
            new Class[]{Throwable.class}, new Class[0]
    );

    public CircuitBreakerConfig {
        if (requestVolumeThreshold <= 0) {
            throw new IllegalArgumentException("requestVolumeThreshold must be > 0");
        }
        if (failureRatio <= 0.0 || failureRatio > 1.0) {
            throw new IllegalArgumentException("failureRatio must be in (0, 1]");
        }
        if (delay <= 0) {
            throw new IllegalArgumentException("delay must be > 0");
        }
        if (successThreshold <= 0) {
            throw new IllegalArgumentException("successThreshold must be > 0");
        }
        failOn = failOn == null ? new Class[]{Throwable.class} : failOn.clone();
        skipOn = skipOn == null ? new Class[0] : skipOn.clone();
    }

    /** Converts the delay to {@link Duration}. */
    public Duration delayDuration() {
        return Duration.of(delay, delayUnit);
    }
}

