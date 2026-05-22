package io.vidocq.dirac.internal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests M1 — Counter, spec MicroProfile Metrics 5.1.1 §3.1.
 */
class CounterImplTest {

    @Test
    void incrementsMonotonically() {
        var counter = new CounterImpl();

        counter.inc();
        counter.inc(4);

        assertEquals(5, counter.getCount());
    }

    @Test
    void rejectsNegativeIncrement() {
        var counter = new CounterImpl();

        assertThrows(IllegalArgumentException.class, () -> counter.inc(-1));
    }

    @Test
    void remainsCorrectUnder200VirtualThreads() throws Exception {
        var counter = new CounterImpl();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 200)
                    .mapToObj(index -> executor.submit(() -> {
                        for (int i = 0; i < 1_000; i++) {
                            counter.inc();
                        }
                    }))
                    .toList();

            for (var future : futures) {
                future.get();
            }
        }

        assertEquals(200_000, counter.getCount());
    }
}

