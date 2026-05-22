package io.vidocq.dirac.internal;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests M4 — Timer, spec MicroProfile Metrics 5.1.1 §3.4.
 */
class TimerImplTest {

    @Test
    void updatesFromDuration() {
        var timer = new TimerImpl();
        timer.update(Duration.ofNanos(10));
        timer.update(Duration.ofNanos(20));

        assertEquals(2, timer.getCount());
        assertEquals(Duration.ofNanos(30), timer.getElapsedTime());
        assertEquals(20d, timer.getSnapshot().getMax());
    }

    @Test
    void timesCallableAndRunnable() throws Exception {
        var timer = new TimerImpl();
        var result = timer.time(() -> "ok");
        timer.time(() -> {
            // no-op body, measured by timer
        });

        assertEquals("ok", result);
        assertEquals(2, timer.getCount());
        assertFalse(timer.getElapsedTime().isNegative());
    }

    @Test
    void timesContextAndStopsOnlyOnce() {
        var timer = new TimerImpl();

        long first;
        long second;
        try (var context = timer.time()) {
            first = context.stop();
            second = context.stop();
        }

        assertTrue(first >= 0);
        assertEquals(0L, second);
        assertEquals(1, timer.getCount());
    }

    @Test
    void rejectsNegativeDuration() {
        var timer = new TimerImpl();

        assertThrows(IllegalArgumentException.class, () -> timer.update(Duration.ofNanos(-1)));
    }

    @Test
    void remainsCorrectUnder200VirtualThreads() throws Exception {
        var timer = new TimerImpl();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 200)
                    .mapToObj(ignored -> executor.submit(() -> {
                        for (int i = 0; i < 1_000; i++) {
                            timer.update(Duration.ofNanos(1));
                        }
                    }))
                    .toList();

            for (var future : futures) {
                future.get();
            }
        }

        assertEquals(200_000, timer.getCount());
        assertEquals(Duration.ofNanos(200_000), timer.getElapsedTime());
    }

    @Test
    void exposesTypedTimerSnapshot() {
        var timer = new TimerImpl();
        timer.update(Duration.ofNanos(3));
        timer.update(Duration.ofNanos(7));

        var snapshot = timer.snapshot();

        assertEquals(2, snapshot.count());
        assertEquals(Duration.ofNanos(10), snapshot.elapsedTime());
        assertEquals(7, snapshot.histogram().max());
    }
}


