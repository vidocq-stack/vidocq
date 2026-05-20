package io.vidocq.humboldt.sdk.metric.data;

import io.opentelemetry.api.common.Attributes;

/**
 * Point de données pour un Sum / Counter long-typé.
 *
 * @param startEpochNanos timestamp de début de la fenêtre cumulative
 * @param epochNanos      timestamp de la collecte
 * @param attributes      labels du point
 * @param value           valeur (cumulée si CUMULATIVE)
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
