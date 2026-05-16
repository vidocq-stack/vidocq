package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryEngineTest {

    @Test
    void retriesOnMatchingExceptionUntilSuccess() throws Exception {
        // MP FT 4.1 §3: retryOn pilote les tentatives supplémentaires.
        AtomicInteger calls = new AtomicInteger();
        RetryConfig config = config(2, 0, 5_000, 0, new Class[]{IOException.class}, new Class[0]);

        Object result = RetryEngine.execute(() -> {
            if (calls.getAndIncrement() < 2) {
                throw new IOException("temporary");
            }
            return "ok";
        }, config);

        assertEquals("ok", result);
        assertEquals(3, calls.get());
    }

    @Test
    void abortOnHasPriorityOverRetryOn() {
        // MP FT 4.1 §3.3: abortOn est prioritaire sur retryOn.
        AtomicInteger calls = new AtomicInteger();
        RetryConfig config = config(
                3,
                0,
                5_000,
                0,
                new Class[]{Exception.class},
                new Class[]{IOException.class}
        );

        IOException error = assertThrows(IOException.class, () -> RetryEngine.execute(() -> {
            calls.incrementAndGet();
            throw new IOException("stop");
        }, config));

        assertEquals("stop", error.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void maxRetriesZeroDoesNotRetry() {
        AtomicInteger calls = new AtomicInteger();
        RetryConfig config = config(0, 0, 5_000, 0, new Class[]{Exception.class}, new Class[0]);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> RetryEngine.execute(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }, config));

        assertEquals("boom", error.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void maxDurationStopsFurtherRetries() {
        AtomicInteger calls = new AtomicInteger();
        RetryConfig config = config(5, 30, 20, 0, new Class[]{Exception.class}, new Class[0]);

        assertThrows(IOException.class, () -> RetryEngine.execute(() -> {
            calls.incrementAndGet();
            throw new IOException("timeout");
        }, config));

        assertEquals(1, calls.get());
    }

    @Test
    void jitterBackoffIsNeverNegative() {
        RetryConfig config = config(1, 1, 5_000, 5, new Class[]{Exception.class}, new Class[0]);

        Duration delay = RetryEngine.computeBackoff(config);

        assertTrue(!delay.isNegative());
        assertTrue(delay.compareTo(config.delayDuration().plus(config.jitterDuration())) <= 0);
    }

    @Test
    void maxDurationBoundaryIsInclusive() {
        // MP FT 4.1 §3.4: maxDuration borne la fenêtre totale de retry.
        boolean shouldStop = RetryEngine.reachesOrExceedsMaxDuration(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ZERO,
                Duration.ZERO
        );

        assertTrue(shouldStop);
    }

    @Test
    void projectedAttemptDurationContributesToMaxDurationLimit() {
        // MP FT 4.1 §3.4: la tentative suivante ne doit pas démarrer hors fenêtre maxDuration.
        boolean shouldStop = RetryEngine.reachesOrExceedsMaxDuration(
                Duration.ofMillis(900),
                Duration.ofSeconds(1),
                Duration.ZERO,
                Duration.ofMillis(150)
        );

        assertTrue(shouldStop);
    }

    private RetryConfig config(
            int maxRetries,
            long delayMillis,
            long maxDurationMillis,
            long jitterMillis,
            Class<? extends Throwable>[] retryOn,
            Class<? extends Throwable>[] abortOn
    ) {
        return new RetryConfig(
                maxRetries,
                delayMillis,
                ChronoUnit.MILLIS,
                maxDurationMillis,
                ChronoUnit.MILLIS,
                jitterMillis,
                ChronoUnit.MILLIS,
                retryOn,
                abortOn
        );
    }
}
