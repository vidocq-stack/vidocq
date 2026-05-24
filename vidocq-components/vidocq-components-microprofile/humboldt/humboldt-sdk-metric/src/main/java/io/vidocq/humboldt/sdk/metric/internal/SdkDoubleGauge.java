package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.metric.aggregation.DoubleLastValueAggregator;

public final class SdkDoubleGauge implements DoubleGauge {

    private final DoubleLastValueAggregator aggregator;

    SdkDoubleGauge(DoubleLastValueAggregator aggregator) { this.aggregator = aggregator; }

    @Override public void set(double value) { set(value, Attributes.empty()); }

    @Override
    public void set(double value, Attributes attributes) {
        aggregator.recordDouble(value, attributes != null ? attributes : Attributes.empty());
    }

    @Override
    public void set(double value, Attributes attributes, Context context) { set(value, attributes); }
}
