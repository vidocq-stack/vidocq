package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.metric.aggregation.SumAggregator;

/**
 * Internal implementation of {@link LongCounter} — delegates to {@link SumAggregator}.
 *
 * <p>Rejects negative values (monotonic counter, aligned with the OTel spec).</p>
 */
public final class SdkLongCounter implements LongCounter {

    private final SumAggregator aggregator;

    SdkLongCounter(SumAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @Override
    public void add(long value) {
        add(value, Attributes.empty());
    }

    @Override
    public void add(long value, Attributes attributes) {
        if (value < 0L) return; // monotonic counter — ignore negatives (OTel alignment)
        aggregator.recordLong(value, attributes != null ? attributes : Attributes.empty());
    }

    @Override
    public void add(long value, Attributes attributes, Context context) {
        add(value, attributes);
    }
}
