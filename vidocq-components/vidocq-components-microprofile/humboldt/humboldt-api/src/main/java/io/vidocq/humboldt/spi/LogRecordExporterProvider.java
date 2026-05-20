package io.vidocq.humboldt.spi;

/**
 * SPI exporters de log records — découvert via {@link java.util.ServiceLoader}.
 *
 * <p>Stub M0 — implémentation effective avec {@code humboldt-sdk-log} en M5.</p>
 */
public interface LogRecordExporterProvider {

    /**
     * @return le nom logique de l'exporter, matche {@code OTEL_LOGS_EXPORTER}.
     */
    String name();
}
