package io.vidocq.dirac.internal;

import io.vidocq.dirac.api.TimerSnapshot;
import org.eclipse.microprofile.metrics.Snapshot;
import org.eclipse.microprofile.metrics.Timer;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Implémentation M4 du timer basée sur nanoTime + histogramme.
 */
final class TimerImpl implements Timer {
    private final HistogramImpl histogram;
    private final LongAdder elapsedNanos = new LongAdder();

    TimerImpl() {
        this(null);
    }

    TimerImpl(String metricName) {
        this.histogram = new HistogramImpl(metricName, true);
    }

    @Override
    public void update(Duration duration) {
        var safeDuration = Objects.requireNonNull(duration, "duration must not be null");
        var nanos = safeDuration.toNanos();
        if (nanos < 0) {
            throw new IllegalArgumentException("Timer duration must be non-negative");
        }
        histogram.update(nanos);
        elapsedNanos.add(nanos);
    }

    @Override
    public <T> T time(Callable<T> callable) throws Exception {
        Objects.requireNonNull(callable, "callable must not be null");
        var start = System.nanoTime();
        try {
            return callable.call();
        } finally {
            update(Duration.ofNanos(System.nanoTime() - start));
        }
    }

    @Override
    public void time(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable must not be null");
        var start = System.nanoTime();
        try {
            runnable.run();
        } finally {
            update(Duration.ofNanos(System.nanoTime() - start));
        }
    }

    @Override
    public Context time() {
        var start = System.nanoTime();
        var closed = new AtomicBoolean();
        return new Context() {
            @Override
            public long stop() {
                if (!closed.compareAndSet(false, true)) {
                    return 0L;
                }
                var duration = System.nanoTime() - start;
                update(Duration.ofNanos(duration));
                return duration;
            }

            @Override
            public void close() {
                stop();
            }
        };
    }

    @Override
    public Duration getElapsedTime() {
        return Duration.ofNanos(elapsedNanos.sum());
    }

    @Override
    public long getCount() {
        return histogram.getCount();
    }

    @Override
    public Snapshot getSnapshot() {
        return histogram.getSnapshot();
    }

    TimerSnapshot snapshot() {
        return new TimerSnapshot(getCount(), getElapsedTime(), histogram.snapshot());
    }
}

