package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.metric.aggregation.ExplicitBucketHistogramAggregator;

/** Long-typed histogram — delegates to ExplicitBucketHistogramAggregator (long→double cast). */
public final class SdkLongHistogram implements LongHistogram {

    private final ExplicitBucketHistogramAggregator aggregator;

    SdkLongHistogram(ExplicitBucketHistogramAggregator aggregator) { this.aggregator = aggregator; }

    @Override public void record(long value) { record(value, Attributes.empty()); }

    @Override
    public void record(long value, Attributes attributes) {
        if (value < 0L) return; // OTel alignment: reject negatives for histograms
        aggregator.recordDouble((double) value, attributes != null ? attributes : Attributes.empty());
    }

    @Override
    public void record(long value, Attributes attributes, Context context) { record(value, attributes); }
}
