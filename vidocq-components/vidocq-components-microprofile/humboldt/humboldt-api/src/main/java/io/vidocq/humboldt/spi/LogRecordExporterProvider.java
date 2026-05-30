package io.vidocq.humboldt.spi;

/**
 * SPI for log record exporters — discovered via {@link java.util.ServiceLoader}.
 *
 * <p>M0 stub — effective implementation arrives with {@code humboldt-sdk-log} in M5.</p>
 */
public interface LogRecordExporterProvider {

    /**
     * @return the logical exporter name, matching {@code OTEL_LOGS_EXPORTER}.
     */
    String name();
}
