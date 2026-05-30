package io.vidocq.humboldt.sdk.metric.data;

/**
 * OpenTelemetry instrument type — used for aggregation selection
 * and OTLP encoding (each type produces a different OTLP sub-message).
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
