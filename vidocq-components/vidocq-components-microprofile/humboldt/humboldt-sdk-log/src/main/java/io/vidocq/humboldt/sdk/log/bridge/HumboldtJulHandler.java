/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
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
 * Each {@link LogRecord} received by this handler is transformed into an OTel LogRecord
 * via {@link Logger#logRecordBuilder()} and emitted to the export pipeline configured
 * in the provided {@link LoggerProvider}.
 *
 * <p><b>Severity mapping</b> (JUL → OTel):
 * <ul>
 *   <li>SEVERE  → ERROR (17)</li>
 *   <li>WARNING → WARN (13)</li>
 *   <li>INFO    → INFO (9)</li>
 *   <li>CONFIG  → DEBUG (5)</li>
 *   <li>FINE/FINER/FINEST → DEBUG (5)/DEBUG2 (6)/DEBUG3 (7)</li>
 * </ul>
 *
 * <p>The {@code instrumentation scope} name is the source {@link java.util.logging.Logger}
 * name (for example {@code "jul-logger"}, {@code "my.app"}). {@link Logger} instances are cached by
 * name to avoid repeated resolution cost.
 *
 * <p>Typical usage: automatic installation on the root JUL Logger by
 * {@code HumboldtAutoConfigure} when the logs SDK is active.
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
        // No-op: LoggerProvider handles flushing on the processor side.
    }

    @Override
    public void close() throws SecurityException {
        // No-op: do not close the shared LoggerProvider.
    }

    /** Formats the JUL message by interpolating parameters ({@link java.text.MessageFormat} style). */
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
     * Mapping from JUL Level → OTel Severity according to standard integer values.
     * Aligned with other OTel implementations (otel-java SDK extensions).
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
     * Short level text for {@code severityText} — used by
     * {@code LoggingLogRecordExporter} to produce readable lines such as
     * {@code "... INFO ..."}, {@code "... WARN ..."}.
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
