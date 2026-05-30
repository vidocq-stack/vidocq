package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongHistogramBuilder;
import io.vidocq.humboldt.sdk.metric.aggregation.ExplicitBucketHistogramAggregator;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;

import java.util.List;

/**
 * Builder for {@link DoubleHistogram} — produces an {@link SdkDoubleHistogram}
 * backed by an {@link ExplicitBucketHistogramAggregator} (default OTel boundaries).
 */
public final class SdkDoubleHistogramBuilder implements DoubleHistogramBuilder {

    private final String name;
    private final SdkMeter meter;
    private String description = "";
    private String unit = "";
    private List<Double> bucketBoundaries = ExplicitBucketHistogramAggregator.DEFAULT_BOUNDARIES;

    SdkDoubleHistogramBuilder(String name, SdkMeter meter) {
        this.name = name;
        this.meter = meter;
    }

    @Override
    public DoubleHistogramBuilder setDescription(String d) {
        this.description = d != null ? d : "";
        return this;
    }

    @Override
    public DoubleHistogramBuilder setUnit(String u) {
        this.unit = u != null ? u : "";
        return this;
    }

    @Override
    public DoubleHistogramBuilder setExplicitBucketBoundariesAdvice(List<Double> boundaries) {
        if (boundaries != null && !boundaries.isEmpty()) this.bucketBoundaries = List.copyOf(boundaries);
        return this;
    }

    @Override
    public LongHistogramBuilder ofLongs() {
        return new SdkLongHistogramBuilder(name, meter, description, unit);
    }

    @Override
    public DoubleHistogram build() {
        ExplicitBucketHistogramAggregator agg = new ExplicitBucketHistogramAggregator(bucketBoundaries);
        SdkDoubleHistogram h = new SdkDoubleHistogram(agg);
        meter.register(new InstrumentEntry(
                name, description, unit,
                InstrumentType.HISTOGRAM, AggregationTemporality.CUMULATIVE,
                /* monotonic */ false, agg));
        return h;
    }
}
