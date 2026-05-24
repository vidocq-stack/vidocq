package io.vidocq.humboldt.sdk.metric.aggregation;

import io.opentelemetry.api.common.Attributes;
import io.vidocq.humboldt.sdk.metric.data.LongPointData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aggregation LastValue pour LongGauge (instrument synchrone) — conserve la dernière
 * valeur enregistrée par set d'attributs. Spec OTel : Gauge expose la valeur courante,
 * pas un cumul.
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
