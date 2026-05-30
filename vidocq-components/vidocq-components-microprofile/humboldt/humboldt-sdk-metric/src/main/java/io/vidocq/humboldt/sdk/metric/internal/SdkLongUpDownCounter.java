package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.metric.aggregation.SumAggregator;

/** Long-typed non-monotonic UpDownCounter — accepts negative values. */
public final class SdkLongUpDownCounter implements LongUpDownCounter {

    private final SumAggregator aggregator;

    SdkLongUpDownCounter(SumAggregator aggregator) { this.aggregator = aggregator; }

    @Override public void add(long value) { add(value, Attributes.empty()); }

    @Override
    public void add(long value, Attributes attributes) {
        aggregator.recordLong(value, attributes != null ? attributes : Attributes.empty());
    }

    @Override
    public void add(long value, Attributes attributes, Context context) { add(value, attributes); }
}
