package io.vidocq.humboldt.sdk.metric.aggregation;

import io.opentelemetry.api.common.Attributes;
import io.vidocq.humboldt.sdk.metric.data.DoublePointData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LastValue aggregation for DoubleGauge — keeps the latest double value
 * recorded for each attribute set.
 */
public final class DoubleLastValueAggregator implements Aggregator<DoublePointData> {

    private final ConcurrentHashMap<Attributes, AtomicReference<Double>> values = new ConcurrentHashMap<>();

    @Override
    public void recordDouble(double value, Attributes attributes) {
        Attributes key = attributes != null ? attributes : Attributes.empty();
        values.computeIfAbsent(key, k -> new AtomicReference<>(0.0)).set(value);
    }

    @Override
    public List<DoublePointData> collect(long startEpochNanos, long epochNanos) {
        List<DoublePointData> out = new ArrayList<>(values.size());
        values.forEach((attrs, holder) ->
                out.add(new DoublePointData(startEpochNanos, epochNanos, attrs, holder.get())));
        return List.copyOf(out);
    }
}
