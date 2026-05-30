/**
 * Humboldt SDK Log — OpenTelemetry implementation of the {@code logs} signal.
 *
 * <p>M5 MVP: SdkLoggerProvider, SdkLogger, SdkLogRecordBuilder, Simple + Batch
 * processors (virtual-thread worker).</p>
 *
 * <p>Deferred to M5b: {@code java.util.logging} and {@code SLF4J} bridges,
 * appender-based sources (to capture logs from existing applications without
 * rewriting the calls).</p>
 */
module io.vidocq.humboldt.sdk.log {

    requires transitive io.vidocq.humboldt.api;
    requires transitive io.vidocq.humboldt.sdk.common;
    requires transitive io.opentelemetry.api;
    requires io.opentelemetry.context;
    requires java.logging;

    exports io.vidocq.humboldt.sdk.log;
    exports io.vidocq.humboldt.sdk.log.bridge;
    exports io.vidocq.humboldt.sdk.log.data;
    exports io.vidocq.humboldt.sdk.log.export;
}
