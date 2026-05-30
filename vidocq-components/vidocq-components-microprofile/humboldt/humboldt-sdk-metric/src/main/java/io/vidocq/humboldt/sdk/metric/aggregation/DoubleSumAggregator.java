package io.vidocq.humboldt.sdk.metric.aggregation;

import io.opentelemetry.api.common.Attributes;
import io.vidocq.humboldt.sdk.metric.data.DoublePointData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Cumulative Sum aggregation for DoubleCounter / DoubleUpDownCounter.
 *
 * <p>Storage: {@link ConcurrentHashMap} keyed by {@link Attributes}; each value
 * is a {@link DoubleAdder} to minimize contention under concurrent writes.</p>
 */
public final class DoubleSumAggregator implements Aggregator<DoublePointData> {

    private final ConcurrentHashMap<Attributes, DoubleAdder> adders = new ConcurrentHashMap<>();

    @Override
    public void recordDouble(double value, Attributes attributes) {
        Attributes key = attributes != null ? attributes : Attributes.empty();
        adders.computeIfAbsent(key, k -> new DoubleAdder()).add(value);
    }

    @Override
    public List<DoublePointData> collect(long startEpochNanos, long epochNanos) {
        List<DoublePointData> out = new ArrayList<>(adders.size());
        adders.forEach((attrs, adder) ->
                out.add(new DoublePointData(startEpochNanos, epochNanos, attrs, adder.sum())));
        return List.copyOf(out);
    }
}
