/**
 * Humboldt Runtime — autoconfig.
 *
 * <p>{@link io.vidocq.humboldt.runtime.HumboldtAutoConfigure#configure()} lit les
 * variables d'environnement {@code OTEL_*} (et un sous-ensemble
 * {@code MP_TELEMETRY_*}) puis assemble :</p>
 * <ul>
 *   <li>{@code SdkTracerProvider} + sampler + Simple/Batch processor + OTLP exporter
 *   <li>{@code SdkMeterProvider} + {@code PeriodicMetricReader} + OTLP exporter
 *   <li>{@code SdkLoggerProvider} + Simple/Batch processor + OTLP exporter
 *   <li>{@code ContextPropagators} W3C (TraceContext + Baggage)
 *   <li>{@code Resource} dérivée de {@code OTEL_SERVICE_NAME} + {@code OTEL_RESOURCE_ATTRIBUTES}
 * </ul>
 *
 * <p>Le handle {@link io.vidocq.humboldt.runtime.AutoConfiguredHumboldt} expose
 * tous les providers et permet un shutdown ordonné.</p>
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

    exports io.vidocq.humboldt.runtime;
}
