package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.metric.aggregation.DoubleSumAggregator;

/** Double-typed monotonic counter (ignores negative values, aligned with the OTel spec). */
public final class SdkDoubleCounter implements DoubleCounter {

    private final DoubleSumAggregator aggregator;

    SdkDoubleCounter(DoubleSumAggregator aggregator) { this.aggregator = aggregator; }

    @Override public void add(double value) { add(value, Attributes.empty()); }

    @Override
    public void add(double value, Attributes attributes) {
        if (value < 0d || Double.isNaN(value)) return; // monotonic + reject NaN
        aggregator.recordDouble(value, attributes != null ? attributes : Attributes.empty());
    }

    @Override
    public void add(double value, Attributes attributes, Context context) { add(value, attributes); }
}
