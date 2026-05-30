package io.vidocq.dirac.internal;

import org.eclipse.microprofile.metrics.Counter;

import java.util.concurrent.atomic.LongAdder;

/**
 * Internal implementation of a monotonic {@link Counter}.
 */
final class CounterImpl implements Counter {
    private final LongAdder value = new LongAdder();

    @Override
    public void inc() {
        value.increment();
    }

    @Override
    public void inc(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("A counter can only be incremented by a non-negative value");
        }
        value.add(n);
    }

    @Override
    public long getCount() {
        return value.sum();
    }
}
