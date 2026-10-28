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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.common.Clock;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;
import io.vidocq.humboldt.sdk.log.export.InMemoryLogRecordExporter;
import io.vidocq.humboldt.sdk.log.export.LogRecordExporter;
import io.vidocq.humboldt.sdk.log.export.LogRecordProcessor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkLogRecordBuilderEdgeCasesTest {

    private static final long CLOCK_NOW = 42_000_000_000L;
    private static final String SCOPE = "edge";
    private static final String KEPT = "kept";
    private static final String LEGACY = "legacy";

    private static final Clock FIXED_CLOCK = new Clock() {
        @Override
        public long now() {
            return CLOCK_NOW;
        }

        @Override
        public long nanoTime() {
            return 0L;
        }
    };

    @Test
    void without_timestamps_both_come_from_the_provider_clock() {
        LogRecordData r = emitOne(b -> b.setBody("now"));
        assertEquals(CLOCK_NOW, r.observedEpochNanos());
        assertEquals(CLOCK_NOW, r.timestampEpochNanos());
    }

    @Test
    void observed_timestamp_set_as_an_instant_also_defaults_the_timestamp() {
        LogRecordData r = emitOne(b -> b.setObservedTimestamp(Instant.ofEpochSecond(3, 4)));
        assertEquals(3_000_000_004L, r.observedEpochNanos());
        assertEquals(3_000_000_004L, r.timestampEpochNanos());
    }

    @Test
    void observed_timestamp_set_with_a_unit_is_converted_to_nanos() {
        LogRecordData r = emitOne(b -> b
                .setObservedTimestamp(5, TimeUnit.MILLISECONDS)
                .setTimestamp(2, TimeUnit.MILLISECONDS));
        assertEquals(5_000_000L, r.observedEpochNanos());
        assertEquals(2_000_000L, r.timestampEpochNanos());
    }

    @Test
    void null_arguments_leave_the_builder_unchanged() {
        LogRecordData r = emitOne(b -> b
                .setSeverity(Severity.WARN).setSeverity(null)
                .setSeverityText("WARN").setSeverityText(null)
                .setBody(KEPT).setBody((String) null).setBody((Value<?>) null)
                .setEventName("evt").setEventName(null)
                .setTimestamp(1, null).setTimestamp((Instant) null)
                .setObservedTimestamp(1, null).setObservedTimestamp((Instant) null)
                .setException(null));
        assertEquals(Severity.WARN, r.severity());
        assertEquals("WARN", r.severityText());
        assertEquals(KEPT, r.body());
        assertEquals("", r.eventName(), "a null event name resets it");
        assertEquals(CLOCK_NOW, r.timestampEpochNanos());
        assertTrue(r.attributes().isEmpty());
    }

    @Test
    void invalid_attributes_are_dropped() {
        LogRecordData r = emitOne(b -> b
                .setAttribute((AttributeKey<String>) null, "v")
                .setAttribute(AttributeKey.stringKey(""), "v")
                .setAttribute(AttributeKey.stringKey("nullValue"), null)
                .setAttribute(AttributeKey.longKey(KEPT), 7L));
        assertEquals(Attributes.of(AttributeKey.longKey(KEPT), 7L), r.attributes());
    }

    @Test
    void an_explicit_context_supplies_the_span_context() {
        SpanContext sc = SpanContext.create(
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                TraceFlags.getSampled(), TraceState.getDefault());
        Context ctx = Context.root().with(Span.wrap(sc));
        LogRecordData r = emitOne(b -> b.setContext(ctx));
        assertEquals(sc, r.spanContext());
    }

    @Test
    void logger_builder_ignores_version_and_schema_and_shares_the_logger() {
        try (SdkLoggerProvider p = SdkLoggerProvider.builder().build()) {
            assertSame(p.get(SCOPE), p.loggerBuilder(SCOPE)
                    .setInstrumentationVersion("1.0")
                    .setSchemaUrl("https://example.org/schema")
                    .build());
            assertSame(p.get(""), p.get(null));
        }
    }

    @Test
    void builder_ignores_null_values() {
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .setResource(null)
                .setClock(null)
                .addLogRecordProcessor(null)
                .build()) {
            assertEquals(Resource.empty(), p.getResource());
            assertTrue(p.flush().isSuccess(), "no processor: flush is an immediate success");
        }
    }

    @Test
    void the_resource_is_carried_to_every_record() {
        Resource resource = Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "svc"));
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .setResource(resource)
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            assertSame(resource, p.getResource());
            p.get(SCOPE).logRecordBuilder().setBody("x").emit();
        }
        assertEquals(resource, exporter.getCollected().getFirst().resource());
    }

    @Test
    void simple_processor_drops_records_after_shutdown_and_shuts_down_once() {
        CountingExporter exporter = new CountingExporter();
        SimpleLogRecordProcessor processor = SimpleLogRecordProcessor.create(exporter);
        assertTrue(processor.flush().isSuccess());
        assertTrue(processor.shutdown().isSuccess());
        assertTrue(processor.shutdown().isSuccess());
        processor.onEmit(record());
        assertEquals(0, exporter.exported.size());
        assertEquals(1, exporter.shutdowns.get());
        assertEquals(1, exporter.flushes.get());
    }

    @Test
    void simple_processor_requires_an_exporter() {
        assertThrows(NullPointerException.class, () -> SimpleLogRecordProcessor.create(null));
    }

    @Test
    void batch_processor_requires_an_exporter() {
        assertThrows(NullPointerException.class, () -> BatchLogRecordProcessor.builder(null));
    }

    @Test
    void batch_processor_ignores_invalid_settings_and_exports_on_flush() {
        CountingExporter exporter = new CountingExporter();
        BatchLogRecordProcessor processor = BatchLogRecordProcessor.builder(exporter)
                .setMaxQueueSize(0)
                .setMaxExportBatchSize(-1)
                .setScheduleDelay(null)
                .setScheduleDelay(Duration.ZERO)
                .setScheduleDelay(Duration.ofSeconds(-1))
                .setScheduleDelay(Duration.ofMinutes(10))
                .build();
        try {
            processor.onEmit(record());
            processor.onEmit(record());
            assertTrue(processor.flush().join(5, TimeUnit.SECONDS).isSuccess());
            assertEquals(2, exporter.exported.size());
            // Nothing queued any more: the flush goes straight to the exporter.
            assertTrue(processor.flush().isSuccess());
            assertEquals(1, exporter.flushes.get());
        } finally {
            processor.shutdown();
        }
        assertEquals(1, exporter.shutdowns.get());
    }

    @Test
    void default_processor_flush_succeeds_and_close_shuts_down() {
        AtomicInteger shutdowns = new AtomicInteger();
        LogRecordProcessor processor = new LogRecordProcessor() {
            @Override
            public void onEmit(LogRecordData record) {
                // records are not needed by this test
            }

            @Override
            public CompletableResultCode shutdown() {
                shutdowns.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }
        };
        assertTrue(processor.flush().isSuccess());
        processor.close();
        assertEquals(1, shutdowns.get());
    }

    @Test
    void default_exporter_close_shuts_down() {
        CountingExporter exporter = new CountingExporter();
        exporter.close();
        assertEquals(1, exporter.shutdowns.get());
    }

    @Test
    void the_legacy_record_constructor_has_no_event_name() {
        LogRecordData r = new LogRecordData(
                null, null, 1L, 2L, SpanContext.getInvalid(),
                null, null, LEGACY, null);
        assertEquals("", r.eventName());
        assertEquals(LEGACY, r.body());
        assertEquals(Value.of(LEGACY), r.bodyValue());
        assertEquals(Severity.UNDEFINED_SEVERITY_NUMBER, r.severity());
        assertEquals("", r.scope().name());
        assertEquals(Resource.empty(), r.resource());
    }

    private static LogRecordData record() {
        return new LogRecordData(
                Resource.empty(), null, 1L, 2L, SpanContext.getInvalid(),
                Severity.INFO, "INFO", "body", Attributes.empty(), "");
    }

    private static LogRecordData emitOne(Consumer<LogRecordBuilder> customizer) {
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .setClock(FIXED_CLOCK)
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            LogRecordBuilder builder = p.get(SCOPE).logRecordBuilder();
            customizer.accept(builder);
            builder.emit();
        }
        assertEquals(1, exporter.getCollected().size());
        return exporter.getCollected().getFirst();
    }

    /** Exporter recording what it receives. */
    private static final class CountingExporter implements LogRecordExporter {
        final List<LogRecordData> exported = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger flushes = new AtomicInteger();
        final AtomicInteger shutdowns = new AtomicInteger();

        @Override
        public CompletableResultCode export(Collection<LogRecordData> records) {
            exported.addAll(new ArrayList<>(records));
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            flushes.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            shutdowns.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        }
    }
}
