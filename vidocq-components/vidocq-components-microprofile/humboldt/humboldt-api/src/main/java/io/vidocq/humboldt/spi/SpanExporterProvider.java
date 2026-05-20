package io.vidocq.humboldt.spi;

/**
 * SPI exporters de spans — découvert via {@link java.util.ServiceLoader}.
 *
 * <p>Stub M0 — la signature complète (avec {@code SpanData}, {@code CompletableResultCode})
 * arrive avec {@code humboldt-sdk-trace} en M2.</p>
 */
public interface SpanExporterProvider {

    /**
     * @return le nom logique de l'exporter, utilisé pour le matcher contre
     *         {@code OTEL_TRACES_EXPORTER} (ex. {@code "otlp"}, {@code "logging"},
     *         {@code "none"}).
     */
    String name();
}
