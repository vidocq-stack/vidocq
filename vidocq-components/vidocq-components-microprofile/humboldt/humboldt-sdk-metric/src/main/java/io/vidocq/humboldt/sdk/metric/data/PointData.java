package io.vidocq.humboldt.sdk.metric.data;

import io.opentelemetry.api.common.Attributes;

/**
 * Marker for metric data points (Sum, Histogram, etc.).
 * Sealed for exhaustive switching on the OTLP encoder side.
 */
public sealed interface PointData
        permits LongPointData, DoublePointData, HistogramPointData {

    long startEpochNanos();

    long epochNanos();

    Attributes attributes();
}
