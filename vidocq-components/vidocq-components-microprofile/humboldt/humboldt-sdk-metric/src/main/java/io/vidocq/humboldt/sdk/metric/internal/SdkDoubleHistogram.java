package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.metric.aggregation.ExplicitBucketHistogramAggregator;

/**
 * Implémentation interne de {@link DoubleHistogram} — délègue à
 * {@link ExplicitBucketHistogramAggregator}.
 *
 * <p>Refuse les valeurs négatives (histogram convention OTel pour durations/sizes).</p>
 */
public final class SdkDoubleHistogram implements DoubleHistogram {

    private final ExplicitBucketHistogramAggregator aggregator;

    SdkDoubleHistogram(ExplicitBucketHistogramAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @Override
    public void record(double value) {
        record(value, Attributes.empty());
    }

    @Override
    public void record(double value, Attributes attributes) {
        if (value < 0.0 || Double.isNaN(value)) return;
        aggregator.recordDouble(value, attributes != null ? attributes : Attributes.empty());
    }

    @Override
    public void record(double value, Attributes attributes, Context context) {
        record(value, attributes);
    }
}
