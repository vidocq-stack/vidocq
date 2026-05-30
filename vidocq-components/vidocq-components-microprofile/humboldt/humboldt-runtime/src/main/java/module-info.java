/**
 * Humboldt Runtime — autoconfig.
 *
 * <p>{@link io.vidocq.humboldt.runtime.HumboldtAutoConfigure#configure()} reads the
 * {@code OTEL_*} environment variables (and a subset of
 * {@code MP_TELEMETRY_*}) and assembles:</p>
 * <ul>
 *   <li>{@code SdkTracerProvider} + sampler + Simple/Batch processor + OTLP exporter
 *   <li>{@code SdkMeterProvider} + {@code PeriodicMetricReader} + OTLP exporter
 *   <li>{@code SdkLoggerProvider} + Simple/Batch processor + OTLP exporter
 *   <li>{@code ContextPropagators} W3C (TraceContext + Baggage)
 *   <li>{@code Resource} derived from {@code OTEL_SERVICE_NAME} + {@code OTEL_RESOURCE_ATTRIBUTES}
 * </ul>
 *
 * <p>The {@link io.vidocq.humboldt.runtime.AutoConfiguredHumboldt} handle exposes
 * all providers and allows an orderly shutdown.</p>
 */
module io.vidocq.humboldt.runtime {

    requires transitive io.vidocq.humboldt.api;
    requires transitive io.vidocq.humboldt.sdk.common;
    requires transitive io.vidocq.humboldt.sdk.trace;
    requires transitive io.vidocq.humboldt.sdk.metric;
    requires transitive io.vidocq.humboldt.sdk.log;
    requires transitive io.vidocq.humboldt.propagator.w3c;
    requires transitive io.vidocq.humboldt.exporter.otlp.http;
    requires transitive io.opentelemetry.api;
    requires java.logging;
    // JvmMetricsBinder uses java.lang.management.* (MemoryMXBean, ThreadMXBean, etc.)
    // + com.sun.management.OperatingSystemMXBean (cpu).
    requires java.management;
    requires jdk.management;

    exports io.vidocq.humboldt.runtime;
}
