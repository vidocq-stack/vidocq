/**
 * Humboldt SDK Metric — implémentation OpenTelemetry du signal {@code metrics}.
 *
 * <p>M4 MVP : instruments synchrones (LongCounter, DoubleHistogram),
 * aggregations CUMULATIVE (Sum, ExplicitBucketHistogram), PeriodicMetricReader
 * sur virtual thread, MetricExporter SPI.</p>
 *
 * <p>Différé en M4b : instruments asynchrones (Observable*), variantes
 * Long/Double manquantes, ExponentialHistogram, ViewRegistry / advice,
 * DELTA temporality.</p>
 */
module io.vidocq.humboldt.sdk.metric {

    requires transitive io.vidocq.humboldt.api;
    requires transitive io.vidocq.humboldt.sdk.common;
    requires transitive io.opentelemetry.api;
    requires java.logging;

    exports io.vidocq.humboldt.sdk.metric;
    exports io.vidocq.humboldt.sdk.metric.data;
    exports io.vidocq.humboldt.sdk.metric.aggregation;
    exports io.vidocq.humboldt.sdk.metric.export;
}
