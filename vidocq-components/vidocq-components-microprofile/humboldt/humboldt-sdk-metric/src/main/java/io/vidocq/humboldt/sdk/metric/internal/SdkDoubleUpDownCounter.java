package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleUpDownCounter;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.metric.aggregation.DoubleSumAggregator;

public final class SdkDoubleUpDownCounter implements DoubleUpDownCounter {

    private final DoubleSumAggregator aggregator;

    SdkDoubleUpDownCounter(DoubleSumAggregator aggregator) { this.aggregator = aggregator; }

    @Override public void add(double value) { add(value, Attributes.empty()); }

    @Override
    public void add(double value, Attributes attributes) {
        if (Double.isNaN(value)) return;
        aggregator.recordDouble(value, attributes != null ? attributes : Attributes.empty());
    }

    @Override
    public void add(double value, Attributes attributes, Context context) { add(value, attributes); }
}
