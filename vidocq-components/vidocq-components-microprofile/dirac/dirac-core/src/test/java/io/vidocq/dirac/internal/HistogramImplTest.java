package io.vidocq.dirac.internal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests M3 — Histogram, spec MicroProfile Metrics 5.1.1 §3.3.
 */
class HistogramImplTest {

    @Test
    void tracksCountSumAndSnapshotStatistics() {
        var histogram = new HistogramImpl();
        histogram.update(10);
        histogram.update(20);
        histogram.update(30);
        histogram.update(40);

        assertEquals(4, histogram.getCount());
        assertEquals(100, histogram.getSum());
        assertEquals(40d, histogram.getSnapshot().getMax());
        assertEquals(25d, histogram.getSnapshot().getMean());
    }

    @Test
    void exposesExpectedPercentiles() {
        var histogram = new HistogramImpl();
        for (int value = 1; value <= 100; value++) {
            histogram.update(value);
        }

        var percentiles = histogram.getSnapshot().percentileValues();
        assertEquals(6, percentiles.length);
        assertEquals(50d, percentiles[0].getValue());
        assertEquals(75d, percentiles[1].getValue());
        assertEquals(95d, percentiles[2].getValue());
        assertEquals(98d, percentiles[3].getValue());
        assertEquals(99d, percentiles[4].getValue());
        assertEquals(100d, percentiles[5].getValue());
    }

    @Test
    void remainsCorrectUnder200VirtualThreads() throws Exception {
        var histogram = new HistogramImpl();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 200)
                    .mapToObj(ignored -> executor.submit(() -> {
                        for (int i = 0; i < 1_000; i++) {
                            histogram.update(1);
                        }
                    }))
                    .toList();

            for (var future : futures) {
                future.get();
            }
        }

        assertEquals(200_000, histogram.getCount());
        assertEquals(200_000, histogram.getSum());
        assertEquals(1d, histogram.getSnapshot().getMax());
    }

    @Test
    void exposesTypedHistogramSnapshot() {
        var histogram = new HistogramImpl();
        histogram.update(3);
        histogram.update(7);
        histogram.update(10);

        var snapshot = histogram.snapshot();

        assertEquals(3, snapshot.count());
        assertEquals(20, snapshot.sum());
        assertEquals(3, snapshot.min());
        assertEquals(10, snapshot.max());
        assertEquals(7d, snapshot.p50());
        assertEquals(10d, snapshot.p75());
        assertEquals(10d, snapshot.p95());
    }
}

