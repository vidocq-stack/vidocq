package io.vidocq.humboldt.sdk.metric.data;

/**
 * Type d'instrument OpenTelemetry — utilisé pour la sélection d'aggregation
 * et l'encodage OTLP (chaque type produit un sous-message OTLP différent).
 */
public enum InstrumentType {
    COUNTER,
    UP_DOWN_COUNTER,
    HISTOGRAM,
    GAUGE,
    OBSERVABLE_COUNTER,
    OBSERVABLE_UP_DOWN_COUNTER,
    OBSERVABLE_GAUGE
}
