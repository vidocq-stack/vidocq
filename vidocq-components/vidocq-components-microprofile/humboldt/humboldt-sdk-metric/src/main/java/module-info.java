/**
 * Humboldt SDK Metric — OpenTelemetry implementation of the {@code metrics} signal.
 *
 * <p>M4 MVP: synchronous instruments (LongCounter, DoubleHistogram),
 * aggregations CUMULATIVE (Sum, ExplicitBucketHistogram), PeriodicMetricReader
 * on a virtual thread, MetricExporter SPI.</p>
 *
 * <p>Deferred to M4b: asynchronous instruments (Observable*), missing
 * Long/Double variants, ExponentialHistogram, ViewRegistry / advice,
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
