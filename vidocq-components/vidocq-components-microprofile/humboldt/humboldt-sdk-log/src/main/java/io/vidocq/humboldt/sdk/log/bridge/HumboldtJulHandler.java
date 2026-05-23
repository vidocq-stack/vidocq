package io.vidocq.humboldt.sdk.log.bridge;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.Severity;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Bridge {@link java.util.logging} (JUL) → OTel {@link Logger}.
 * <p>
 * Chaque {@link LogRecord} reçu sur ce handler est transformé en LogRecord OTel
 * via {@link Logger#logRecordBuilder()} et émis vers le pipeline d'export configuré
 * dans le {@link LoggerProvider} fourni.
 *
 * <p><b>Mapping severity</b> (JUL → OTel) :
 * <ul>
 *   <li>SEVERE  → ERROR (17)</li>
 *   <li>WARNING → WARN (13)</li>
 *   <li>INFO    → INFO (9)</li>
 *   <li>CONFIG  → DEBUG (5)</li>
 *   <li>FINE/FINER/FINEST → DEBUG (5)/DEBUG2 (6)/DEBUG3 (7)</li>
 * </ul>
 *
 * <p>Le nom de l'{@code instrumentation scope} est celui du {@link java.util.logging.Logger}
 * source (ex. {@code "jul-logger"}, {@code "my.app"}). Cache de {@link Logger} par
 * nom pour éviter le coût de résolution.
 *
 * <p>Usage typique : installation automatique sur le root JUL Logger par
 * {@code HumboldtAutoConfigure} quand le SDK logs est actif.
 */
public final class HumboldtJulHandler extends Handler {

    private final LoggerProvider loggerProvider;
    private final ConcurrentMap<String, Logger> loggerCache = new ConcurrentHashMap<>();

    public HumboldtJulHandler(OpenTelemetry openTelemetry) {
        this(openTelemetry.getLogsBridge());
    }

    public HumboldtJulHandler(LoggerProvider loggerProvider) {
        this.loggerProvider = loggerProvider;
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null) return;
        if (!isLoggable(record)) return;
        String scopeName = record.getLoggerName() != null ? record.getLoggerName() : "";
        Logger otelLogger = loggerCache.computeIfAbsent(scopeName, loggerProvider::get);

        String message = formatMessage(record);
        Severity severity = mapSeverity(record.getLevel());
        String severityText = severityText(record.getLevel());

        otelLogger.logRecordBuilder()
                .setBody(message)
                .setSeverity(severity)
                .setSeverityText(severityText)
                .setTimestamp(record.getInstant())
                .emit();
    }

    @Override
    public void flush() {
        // Pas-op : le LoggerProvider gère le flush côté processors.
    }

    @Override
    public void close() throws SecurityException {
        // Pas-op : ne fermons pas le LoggerProvider partagé.
    }

    /** Formate le message JUL en interpolant les paramètres (style {@link java.text.MessageFormat}). */
    private static String formatMessage(LogRecord record) {
        String raw = record.getMessage();
        if (raw == null) return "";
        Object[] params = record.getParameters();
        if (params == null || params.length == 0) return raw;
        try {
            return java.text.MessageFormat.format(raw, params);
        } catch (IllegalArgumentException _) {
            return raw;
        }
    }

    /**
     * Mapping JUL Level → OTel Severity selon les valeurs entières standard.
     * Aligné sur les autres implémentations OTel (otel-java SDK extensions).
     */
    static Severity mapSeverity(Level level) {
        if (level == null) return Severity.INFO;
        int v = level.intValue();
        if (v >= Level.SEVERE.intValue()) return Severity.ERROR;
        if (v >= Level.WARNING.intValue()) return Severity.WARN;
        if (v >= Level.INFO.intValue()) return Severity.INFO;
        if (v >= Level.CONFIG.intValue()) return Severity.DEBUG;
        if (v >= Level.FINE.intValue()) return Severity.DEBUG;
        if (v >= Level.FINER.intValue()) return Severity.DEBUG2;
        if (v >= Level.FINEST.intValue()) return Severity.DEBUG3;
        return Severity.TRACE;
    }

    /**
     * Texte abrégé du level pour {@code severityText} — utilisé par
     * {@code LoggingLogRecordExporter} pour produire des lignes lisibles
     * type {@code "... INFO ..."}, {@code "... WARN ..."}.
     */
    static String severityText(Level level) {
        if (level == null) return "INFO";
        if (level == Level.SEVERE) return "ERROR";
        if (level == Level.WARNING) return "WARN";
        if (level == Level.INFO) return "INFO";
        if (level == Level.CONFIG) return "DEBUG";
        if (level == Level.FINE) return "DEBUG";
        if (level == Level.FINER) return "TRACE";
        if (level == Level.FINEST) return "TRACE";
        return level.getName();
    }
}
