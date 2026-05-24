package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongHistogramBuilder;
import io.vidocq.humboldt.sdk.metric.aggregation.ExplicitBucketHistogramAggregator;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;

import java.util.List;

public final class SdkLongHistogramBuilder implements LongHistogramBuilder {

    private final String name;
    private final SdkMeter meter;
    private String description;
    private String unit;
    private List<Double> bucketBoundaries = ExplicitBucketHistogramAggregator.DEFAULT_BOUNDARIES;

    SdkLongHistogramBuilder(String name, SdkMeter meter, String description, String unit) {
        this.name = name; this.meter = meter;
        this.description = description != null ? description : "";
        this.unit = unit != null ? unit : "";
    }

    @Override public LongHistogramBuilder setDescription(String d) { this.description = d != null ? d : ""; return this; }
    @Override public LongHistogramBuilder setUnit(String u) { this.unit = u != null ? u : ""; return this; }

    @Override
    public LongHistogramBuilder setExplicitBucketBoundariesAdvice(List<Long> boundaries) {
        if (boundaries != null && !boundaries.isEmpty()) {
            this.bucketBoundaries = boundaries.stream().map(Long::doubleValue).toList();
        }
        return this;
    }

    @Override
    public LongHistogram build() {
        ExplicitBucketHistogramAggregator agg = new ExplicitBucketHistogramAggregator(bucketBoundaries);
        SdkLongHistogram h = new SdkLongHistogram(agg);
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.HISTOGRAM, AggregationTemporality.CUMULATIVE, false, agg));
        return h;
    }
}
