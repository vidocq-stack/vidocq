package io.vidocq.heisenberg.internal;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

public record RetryConfig(
        int maxRetries,
        long delay,
        ChronoUnit delayUnit,
        long maxDuration,
        ChronoUnit durationUnit,
        long jitter,
        ChronoUnit jitterDelayUnit,
        Class<? extends Throwable>[] retryOn,
        Class<? extends Throwable>[] abortOn
) {
    public RetryConfig {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        if (delay < 0) {
            throw new IllegalArgumentException("delay must be >= 0");
        }
        if (maxDuration < 0) {
            throw new IllegalArgumentException("maxDuration must be >= 0");
        }
        if (jitter < 0) {
            throw new IllegalArgumentException("jitter must be >= 0");
        }
        retryOn = retryOn == null ? new Class[]{Exception.class} : retryOn.clone();
        abortOn = abortOn == null ? new Class[0] : abortOn.clone();
    }

    public Duration delayDuration() {
        return Duration.of(delay, delayUnit);
    }

    public Duration maxDurationValue() {
        return Duration.of(maxDuration, durationUnit);
    }

    public Duration jitterDuration() {
        return Duration.of(jitter, jitterDelayUnit);
    }
}
