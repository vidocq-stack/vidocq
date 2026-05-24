package io.vidocq.humboldt.sdk.metric.data;

import io.opentelemetry.api.common.Attributes;

/**
 * Point de données pour un Sum / Counter / Gauge double-typé.
 *
 * @param startEpochNanos timestamp de début de la fenêtre cumulative
 * @param epochNanos      timestamp de la collecte
 * @param attributes      labels du point
 * @param value           valeur (cumulée pour Sum, dernière valeur pour Gauge)
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
