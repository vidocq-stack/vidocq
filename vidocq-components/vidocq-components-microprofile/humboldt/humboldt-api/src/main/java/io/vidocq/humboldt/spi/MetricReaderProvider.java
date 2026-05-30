package io.vidocq.humboldt.spi;

/**
 * SPI for metric readers — discovered via {@link java.util.ServiceLoader}.
 *
 * <p>M0 stub — effective implementation arrives with {@code humboldt-sdk-metric} in M4.</p>
 */
public interface MetricReaderProvider {

    /**
     * @return the logical reader name, matching {@code OTEL_METRICS_EXPORTER}.
     */
    String name();
}
