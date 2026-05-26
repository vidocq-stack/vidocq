package io.vidocq.heisenberg.internal;

import io.vidocq.heisenberg.api.FtMetricsRecorder;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;

public final class RetryEngine {

    private RetryEngine() {}

    public static Object execute(PolicyComposer.Invocation invocation, RetryConfig config) throws Exception {
        return execute(invocation, config, FtMetricsRecorder.NOOP, null, null);
    }

    public static Object execute(PolicyComposer.Invocation invocation, RetryConfig config,
                                 FtMetricsRecorder recorder, Class<?> beanClass, Method method) throws Exception {
        long startedAt = System.nanoTime();
        int attempt = 0;

        while (true) {
            long attemptStartedAt = System.nanoTime();
            try {
                Object result = invocation.proceed();
                recorder.recordRetry(beanClass, method, attempt, FtMetricsRecorder.RetryResult.VALUE_RETURNED);
                return result;
            } catch (Throwable failure) {
                Throwable effectiveFailure = unwrapInvocationFailure(failure);
                Duration attemptDuration = Duration.ofNanos(System.nanoTime() - attemptStartedAt);
                if (!shouldRetry(effectiveFailure, config)) {
                    recorder.recordRetry(beanClass, method, attempt, FtMetricsRecorder.RetryResult.EXCEPTION_NOT_RETRYABLE);
                    throwAsException(effectiveFailure);
                }

                // MP FT 4.1 §3.4 : maxRetries = -1 signifie « retry indefinitely ».
                if (config.maxRetries() >= 0 && attempt >= config.maxRetries()) {
                    recorder.recordRetry(beanClass, method, attempt, FtMetricsRecorder.RetryResult.MAX_RETRIES_REACHED);
                    throwAsException(effectiveFailure);
                }

                Duration sleepDuration = computeBackoff(config);
                if (exceedsMaxDuration(startedAt, config.maxDurationValue(), sleepDuration, attemptDuration)) {
                    recorder.recordRetry(beanClass, method, attempt, FtMetricsRecorder.RetryResult.MAX_DURATION_REACHED);
                    throwAsException(effectiveFailure);
                }

                sleep(sleepDuration);
                attempt++;
            }
        }
    }

    static boolean shouldRetry(Throwable failure, RetryConfig config) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (failure instanceof java.util.concurrent.CancellationException) {
            return false;
        }
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

    private static Throwable unwrapInvocationFailure(Throwable failure) {
        if (failure instanceof InvocationTargetException ite && ite.getCause() != null) {
            return ite.getCause();
        }
        if (failure instanceof ExecutionException ee && ee.getCause() != null) {
            return ee.getCause();
        }
        return failure;
    }
}

