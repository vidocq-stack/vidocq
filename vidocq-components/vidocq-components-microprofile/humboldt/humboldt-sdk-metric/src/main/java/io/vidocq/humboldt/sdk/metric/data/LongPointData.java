package io.vidocq.humboldt.sdk.metric.data;

import io.opentelemetry.api.common.Attributes;

/**
 * Data point for a long-typed Sum / Counter.
 *
 * @param startEpochNanos start timestamp of the cumulative window
 * @param epochNanos      collection timestamp
 * @param attributes      point labels
 * @param value           value (cumulative if CUMULATIVE)
 */
public record LongPointData(
        long startEpochNanos,
        long epochNanos,
        Attributes attributes,
        long value) implements PointData {

    public LongPointData {
        if (attributes == null) attributes = Attributes.empty();
    }
}
