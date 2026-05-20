package io.vidocq.humboldt.spi;

/**
 * SPI metric readers — découvert via {@link java.util.ServiceLoader}.
 *
 * <p>Stub M0 — implémentation effective avec {@code humboldt-sdk-metric} en M4.</p>
 */
public interface MetricReaderProvider {

    /**
     * @return le nom logique du reader, matche {@code OTEL_METRICS_EXPORTER}.
     */
    String name();
}
