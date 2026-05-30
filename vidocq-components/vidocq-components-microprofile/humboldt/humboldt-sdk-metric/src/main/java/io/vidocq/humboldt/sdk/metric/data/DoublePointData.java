package io.vidocq.humboldt.sdk.metric.data;

import io.opentelemetry.api.common.Attributes;

/**
 * Data point for a double-typed Sum / Counter / Gauge.
 *
 * @param startEpochNanos start timestamp of the cumulative window
 * @param epochNanos      collection timestamp
 * @param attributes      point labels
 * @param value           value (cumulative for Sum, latest value for Gauge)
 */
public record DoublePointData(
        long startEpochNanos,
        long epochNanos,
        Attributes attributes,
        double value) implements PointData {

    public DoublePointData {
        if (attributes == null) attributes = Attributes.empty();
    }
}
