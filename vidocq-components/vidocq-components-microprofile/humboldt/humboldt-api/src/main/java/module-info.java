/**
 * Humboldt — MicroProfile Telemetry 2.1 implementation.
 *
 * <p>API module: stable public surface. Exports the {@code Humboldt} facade
 * and the SPI interfaces consumed by the SDK modules (trace, metric, log)
 * and by Vidocq extensions (chappe, cassini, vauban).</p>
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
