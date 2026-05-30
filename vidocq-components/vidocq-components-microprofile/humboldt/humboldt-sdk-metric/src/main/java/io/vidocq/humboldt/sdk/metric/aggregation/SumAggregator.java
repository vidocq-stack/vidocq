package io.vidocq.humboldt.sdk.metric.aggregation;

import io.opentelemetry.api.common.Attributes;
import io.vidocq.humboldt.sdk.metric.data.LongPointData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Cumulative Sum aggregation for long Counter / UpDownCounter.
 *
 * <p>Storage: {@link ConcurrentHashMap} keyed by {@link Attributes}; each
 * value is a {@link LongAdder} to minimize contention under heavy concurrent
 * writes.</p>
 */
public final class SumAggregator implements Aggregator<LongPointData> {

    private final ConcurrentHashMap<Attributes, LongAdder> adders = new ConcurrentHashMap<>();

    @Override
    public void recordLong(long value, Attributes attributes) {
        Attributes key = attributes != null ? attributes : Attributes.empty();
        adders.computeIfAbsent(key, k -> new LongAdder()).add(value);
    }

    @Override
    public List<LongPointData> collect(long startEpochNanos, long epochNanos) {
        List<LongPointData> out = new ArrayList<>(adders.size());
        adders.forEach((attrs, adder) ->
                out.add(new LongPointData(startEpochNanos, epochNanos, attrs, adder.sum())));
        return List.copyOf(out);
    }
}
