package io.vidocq.humboldt.sdk.metric.internal;

import io.vidocq.humboldt.sdk.metric.aggregation.Aggregator;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;
import io.vidocq.humboldt.sdk.metric.data.PointData;

/**
 * Entry registered by an {@link io.vidocq.humboldt.sdk.metric.internal.SdkMeter}
 * for each created instrument — aggregates descriptor + aggregator.
 */
public record InstrumentEntry(
        String name,
        String description,
        String unit,
        InstrumentType type,
        AggregationTemporality temporality,
        boolean monotonic,
        Aggregator<? extends PointData> aggregator) {

    public InstrumentEntry {
        if (name == null) throw new NullPointerException("name");
        if (description == null) description = "";
        if (unit == null) unit = "";
        if (type == null) throw new NullPointerException("type");
        if (temporality == null) temporality = AggregationTemporality.CUMULATIVE;
        if (aggregator == null) throw new NullPointerException("aggregator");
    }
}
