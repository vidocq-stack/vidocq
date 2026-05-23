/**
 * Humboldt SDK Log — implémentation OpenTelemetry du signal {@code logs}.
 *
 * <p>M5 MVP : SdkLoggerProvider, SdkLogger, SdkLogRecordBuilder, processors
 * Simple + Batch (worker virtual thread).</p>
 *
 * <p>Différé en M5b : bridges {@code java.util.logging} et {@code SLF4J},
 * appender-based source (pour capturer les logs d'applications existantes
 * sans réécrire les appels).</p>
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
