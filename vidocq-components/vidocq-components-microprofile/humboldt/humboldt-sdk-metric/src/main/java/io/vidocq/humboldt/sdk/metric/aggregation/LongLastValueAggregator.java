package io.vidocq.humboldt.sdk.metric.aggregation;

import io.opentelemetry.api.common.Attributes;
import io.vidocq.humboldt.sdk.metric.data.LongPointData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LastValue aggregation for LongGauge (synchronous instrument) — keeps the latest
 * value recorded for each attribute set. OTel spec: Gauge exposes the current value,
 * not a cumulative total.
 */
public final class LongLastValueAggregator implements Aggregator<LongPointData> {

    private final ConcurrentHashMap<Attributes, AtomicLong> values = new ConcurrentHashMap<>();

    @Override
    public void recordLong(long value, Attributes attributes) {
        Attributes key = attributes != null ? attributes : Attributes.empty();
        values.computeIfAbsent(key, k -> new AtomicLong()).set(value);
    }

    @Override
    public List<LongPointData> collect(long startEpochNanos, long epochNanos) {
        List<LongPointData> out = new ArrayList<>(values.size());
        values.forEach((attrs, holder) ->
                out.add(new LongPointData(startEpochNanos, epochNanos, attrs, holder.get())));
        return List.copyOf(out);
    }
}
