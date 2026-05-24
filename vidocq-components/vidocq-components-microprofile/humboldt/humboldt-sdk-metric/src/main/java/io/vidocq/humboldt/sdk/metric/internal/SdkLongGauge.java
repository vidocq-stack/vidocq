package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongGauge;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.metric.aggregation.LongLastValueAggregator;

public final class SdkLongGauge implements LongGauge {

    private final LongLastValueAggregator aggregator;

    SdkLongGauge(LongLastValueAggregator aggregator) { this.aggregator = aggregator; }

    @Override public void set(long value) { set(value, Attributes.empty()); }

    @Override
    public void set(long value, Attributes attributes) {
        aggregator.recordLong(value, attributes != null ? attributes : Attributes.empty());
    }

    @Override
    public void set(long value, Attributes attributes, Context context) { set(value, attributes); }
}
