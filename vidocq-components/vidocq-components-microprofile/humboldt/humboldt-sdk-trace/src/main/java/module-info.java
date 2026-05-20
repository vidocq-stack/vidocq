/**
 * Humboldt SDK Trace — implémentation OpenTelemetry du signal {@code traces}.
 *
 * <ul>
 *   <li>{@link io.vidocq.humboldt.sdk.trace.SdkTracerProvider} — factory immutable de {@code Tracer}</li>
 *   <li>Samplers : always_on, always_off, parentbased, traceidratio</li>
 *   <li>Processors : {@code SimpleSpanProcessor} (synchrone), {@code BatchSpanProcessor} (virtual-thread)</li>
 *   <li>Exporters utilitaires : {@code InMemorySpanExporter}, {@code LoggingSpanExporter}</li>
 * </ul>
 *
 * <p>La sérialisation OTLP (HTTP/protobuf) est livrée par le module séparé
 * {@code humboldt-exporter-otlp-http} en M3.</p>
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
