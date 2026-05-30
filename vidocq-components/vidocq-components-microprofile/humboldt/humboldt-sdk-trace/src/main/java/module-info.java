/**
 * Humboldt SDK Trace — OpenTelemetry implementation of the {@code traces} signal.
 *
 * <ul>
 *   <li>{@link io.vidocq.humboldt.sdk.trace.SdkTracerProvider} — immutable {@code Tracer} factory</li>
 *   <li>Samplers: always_on, always_off, parentbased, traceidratio</li>
 *   <li>Processors: {@code SimpleSpanProcessor} (synchronous), {@code BatchSpanProcessor} (virtual-thread)</li>
 *   <li>Utility exporters: {@code InMemorySpanExporter}, {@code LoggingSpanExporter}</li>
 * </ul>
 *
 * <p>OTLP serialization (HTTP/protobuf) is provided by the separate
 * {@code humboldt-exporter-otlp-http} module in M3.</p>
 */
module io.vidocq.humboldt.sdk.trace {

    requires transitive io.vidocq.humboldt.api;
    requires transitive io.vidocq.humboldt.sdk.common;
    requires transitive io.opentelemetry.api;
    requires io.opentelemetry.context;
    requires java.logging;

    exports io.vidocq.humboldt.sdk.trace;
    exports io.vidocq.humboldt.sdk.trace.data;
    exports io.vidocq.humboldt.sdk.trace.export;
    exports io.vidocq.humboldt.sdk.trace.samplers;
}
