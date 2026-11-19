/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
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
 * M4 timer implementation based on nanoTime + histogram.
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
