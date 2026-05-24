package io.vidocq.humboldt.sdk.metric.data;

import io.opentelemetry.api.common.Attributes;

/**
 * Marqueur pour les points de données métriques (Sum, Histogram, etc.).
 * Sealed pour exhaustivité du switch côté encoder OTLP.
 */
public sealed interface PointData
        permits LongPointData, DoublePointData, HistogramPointData {

    long startEpochNanos();

    long epochNanos();

    Attributes attributes();
}
