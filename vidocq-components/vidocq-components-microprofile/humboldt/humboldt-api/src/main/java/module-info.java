/**
 * Humboldt — MicroProfile Telemetry 2.1 implementation.
 *
 * <p>Module API : surface publique stable. Exporte la façade {@code Humboldt}
 * et les interfaces SPI consommées par les modules SDK (trace, metric, log)
 * et par les extensions Vidocq (chappe, cassini, vauban).</p>
 */
module io.vidocq.humboldt.api {

    exports io.vidocq.humboldt;
    exports io.vidocq.humboldt.spi;

    uses io.vidocq.humboldt.spi.SpanExporterProvider;
    uses io.vidocq.humboldt.spi.MetricReaderProvider;
    uses io.vidocq.humboldt.spi.LogRecordExporterProvider;
    uses io.vidocq.humboldt.spi.SamplerProvider;
    uses io.vidocq.humboldt.spi.ResourceProvider;
}
