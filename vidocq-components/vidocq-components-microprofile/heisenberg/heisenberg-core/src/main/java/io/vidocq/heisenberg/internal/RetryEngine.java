package io.vidocq.heisenberg.internal;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public final class RetryEngine {

    private RetryEngine() {}

    public static Object execute(PolicyComposer.Invocation invocation, RetryConfig config) throws Exception {
        long startedAt = System.nanoTime();
        int attempt = 0;

        while (true) {
            long attemptStartedAt = System.nanoTime();
            try {
                return invocation.proceed();
            } catch (Throwable failure) {
                Duration attemptDuration = Duration.ofNanos(System.nanoTime() - attemptStartedAt);
                if (!shouldRetry(failure, config)) {
                    throwAsException(failure);
                }

                if (attempt >= config.maxRetries()) {
                    throwAsException(failure);
                }

                Duration sleepDuration = computeBackoff(config);
                if (exceedsMaxDuration(startedAt, config.maxDurationValue(), sleepDuration, attemptDuration)) {
                    throwAsException(failure);
                }

                sleep(sleepDuration);
                attempt++;
            }
        }
    }

    static boolean shouldRetry(Throwable failure, RetryConfig config) {
        if (matchesAny(failure, config.abortOn())) {
            return false;
        }
        return matchesAny(failure, config.retryOn());
    }

    static Duration computeBackoff(RetryConfig config) {
        Duration delay = config.delayDuration();
        Duration jitter = config.jitterDuration();

        if (jitter.isZero()) {
            return delay;
        }

        long jitterNanos = jitter.toNanos();
        long randomJitter = ThreadLocalRandom.current().nextLong(-jitterNanos, jitterNanos + 1);
        long effectiveDelay = Math.max(0L, delay.toNanos() + randomJitter);
        return Duration.ofNanos(effectiveDelay);
    }

    private static boolean matchesAny(Throwable failure, Class<? extends Throwable>[] types) {
        for (Class<? extends Throwable> type : types) {
            if (type.isAssignableFrom(failure.getClass())) {
                return true;
            }
        }
        return false;
    }

    private static boolean exceedsMaxDuration(
            long startedAtNanos,
            Duration maxDuration,
            Duration nextDelay,
            Duration estimatedAttemptDuration
    ) {
        if (maxDuration.isZero()) {
            return true;
        }
        long elapsed = System.nanoTime() - startedAtNanos;
        return reachesOrExceedsMaxDuration(Duration.ofNanos(elapsed), maxDuration, nextDelay, estimatedAttemptDuration);
    }

    static boolean reachesOrExceedsMaxDuration(
            Duration elapsed,
            Duration maxDuration,
            Duration nextDelay,
            Duration estimatedAttemptDuration
    ) {
        return elapsed.plus(nextDelay).plus(estimatedAttemptDuration).compareTo(maxDuration) >= 0;
    }

    private static void sleep(Duration duration) throws Exception {
        if (duration.isZero()) {
            return;
        }

        try {
            long millis = duration.toMillis();
            int nanos = (int) duration.minusMillis(millis).toNanos();
            Thread.sleep(millis, nanos);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static void throwAsException(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new RuntimeException(failure);
    }
}

