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
package io.vidocq.humboldt.sdk.log;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.vidocq.humboldt.sdk.log.bridge.HumboldtJulHandler;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;
import io.vidocq.humboldt.sdk.log.export.InMemoryLogRecordExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumboldtJulHandlerMappingTest {

    private static final String LOGGER_NAME = "my.app";

    /** A level between INFO (800) and WARNING (900) that JUL does not define. */
    private static final Level NOTICE = new Level("NOTICE", 850) { };

    static Stream<Arguments> levels() {
        return Stream.of(
                Arguments.of(Level.SEVERE, Severity.ERROR, "ERROR"),
                Arguments.of(Level.WARNING, Severity.WARN, "WARN"),
                Arguments.of(Level.INFO, Severity.INFO, "INFO"),
                Arguments.of(Level.CONFIG, Severity.DEBUG, "DEBUG"),
                Arguments.of(Level.FINE, Severity.DEBUG, "DEBUG"),
                Arguments.of(Level.FINER, Severity.DEBUG2, "TRACE"),
                Arguments.of(Level.FINEST, Severity.DEBUG3, "TRACE"),
                Arguments.of(Level.ALL, Severity.TRACE, "ALL"),
                Arguments.of(NOTICE, Severity.INFO, "NOTICE"));
    }

    @ParameterizedTest
    @MethodSource("levels")
    void maps_the_jul_level_to_the_otel_severity_and_text(Level level, Severity severity, String text) {
        LogRecordData r = publishOne(sampleRecord(level, "message"));
        assertEquals(severity, r.severity());
        assertEquals(text, r.severityText());
    }

    @Test
    void the_record_timestamp_is_the_jul_instant() {
        LogRecord logRecord = sampleRecord(Level.INFO, "at a fixed time");
        logRecord.setInstant(Instant.ofEpochSecond(1_700_000_000L, 123));
        LogRecordData r = publishOne(logRecord);
        assertEquals(1_700_000_000_000_000_123L, r.timestampEpochNanos());
    }

    @Test
    void a_record_without_a_logger_name_uses_the_empty_scope() {
        LogRecord logRecord = new LogRecord(Level.INFO, "anonymous");
        LogRecordData r = publishOne(logRecord);
        assertEquals("", r.scope().name());
        assertEquals("anonymous", r.body());
    }

    @Test
    void a_record_without_a_message_has_an_empty_body() {
        LogRecordData r = publishOne(sampleRecord(Level.INFO, null));
        assertEquals("", r.body());
    }

    @Test
    void an_invalid_message_pattern_is_kept_raw() {
        LogRecord logRecord = sampleRecord(Level.INFO, "broken {0");
        logRecord.setParameters(new Object[] {"x"});
        assertEquals("broken {0", publishOne(logRecord).body());
    }

    @Test
    void an_empty_parameter_array_leaves_the_message_unformatted() {
        LogRecord logRecord = sampleRecord(Level.INFO, "literal {0}");
        logRecord.setParameters(new Object[0]);
        assertEquals("literal {0}", publishOne(logRecord).body());
    }

    @Test
    void a_null_record_and_a_record_below_the_handler_level_are_dropped() {
        List<LogRecordData> out = publish(handler -> {
            handler.setLevel(Level.WARNING);
            handler.publish(null);
            handler.publish(sampleRecord(Level.INFO, "too low"));
            handler.publish(sampleRecord(Level.SEVERE, "kept"));
        });
        assertEquals(1, out.size());
        assertEquals("kept", out.getFirst().body());
    }

    @Test
    void loggers_are_cached_per_jul_logger_name() {
        List<LogRecordData> out = publish(handler -> {
            handler.publish(sampleRecord(Level.INFO, "one"));
            handler.publish(sampleRecord(Level.INFO, "two"));
            LogRecord other = new LogRecord(Level.INFO, "three");
            other.setLoggerName("other.app");
            handler.publish(other);
        });
        assertEquals(3, out.size());
        assertEquals(LOGGER_NAME, out.get(0).scope().name());
        assertEquals(LOGGER_NAME, out.get(1).scope().name());
        assertEquals("other.app", out.get(2).scope().name());
    }

    @Test
    void flush_and_close_leave_the_shared_provider_open() {
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            HumboldtJulHandler handler = new HumboldtJulHandler(p);
            handler.flush();
            handler.close();
            // The provider still exports after the handler was closed.
            new HumboldtJulHandler(p).publish(sampleRecord(Level.INFO, "after close"));
        }
        assertEquals("after close", exporter.getCollected().getFirst().body());
    }

    @Test
    void the_open_telemetry_constructor_uses_its_logs_bridge() {
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            new HumboldtJulHandler(openTelemetryWith(p)).publish(sampleRecord(Level.WARNING, "via OpenTelemetry"));
        }
        LogRecordData r = exporter.getCollected().getFirst();
        assertEquals("via OpenTelemetry", r.body());
        assertEquals(Severity.WARN, r.severity());
    }

    private static OpenTelemetry openTelemetryWith(LoggerProvider logs) {
        return new OpenTelemetry() {
            @Override
            public TracerProvider getTracerProvider() {
                return TracerProvider.noop();
            }

            @Override
            public ContextPropagators getPropagators() {
                return ContextPropagators.noop();
            }

            @Override
            public LoggerProvider getLogsBridge() {
                return logs;
            }
        };
    }

    private static LogRecord sampleRecord(Level level, String message) {
        LogRecord logRecord = new LogRecord(level, message);
        logRecord.setLoggerName(LOGGER_NAME);
        return logRecord;
    }

    private static LogRecordData publishOne(LogRecord logRecord) {
        List<LogRecordData> out = publish(handler -> handler.publish(logRecord));
        assertEquals(1, out.size());
        return out.getFirst();
    }

    private static List<LogRecordData> publish(Consumer<Handler> action) {
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            action.accept(new HumboldtJulHandler(p));
        }
        assertTrue(exporter.getCollected().stream().allMatch(r -> r.attributes().isEmpty()));
        return exporter.getCollected();
    }
}
