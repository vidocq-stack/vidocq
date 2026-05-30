package io.vidocq.humboldt.spi;

/**
 * SPI for span exporters — discovered via {@link java.util.ServiceLoader}.
 *
 * <p>M0 stub — the full signature (with {@code SpanData}, {@code CompletableResultCode})
 * arrives with {@code humboldt-sdk-trace} in M2.</p>
 */
public interface SpanExporterProvider {

    /**
     * @return the logical exporter name, used to match against
     *         {@code OTEL_TRACES_EXPORTER} (for example {@code "otlp"}, {@code "logging"},
     *         {@code "none"}).
     */
    String name();
}
