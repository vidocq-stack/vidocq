package io.vidocq.humboldt.sdk.log;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;
import io.vidocq.humboldt.sdk.log.export.InMemoryLogRecordExporter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkLoggerProviderTest {

    @Test
    void emit_records_visible_in_exporter() {
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .setResource(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "humboldt-log-test")))
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            Logger logger = p.get("io.vidocq.test");
            logger.logRecordBuilder()
                    .setSeverity(Severity.INFO)
                    .setSeverityText("INFO")
                    .setBody("hello humboldt")
                    .setAttribute(AttributeKey.stringKey("user.id"), "u-42")
                    .emit();
        }

        List<LogRecordData> records = exporter.getCollected();
        assertEquals(1, records.size());
        LogRecordData r = records.getFirst();
        assertEquals("hello humboldt", r.body());
        assertEquals(Severity.INFO, r.severity());
        assertEquals("INFO", r.severityText());
        assertEquals("u-42", r.attributes().get(AttributeKey.stringKey("user.id")));
        assertEquals("humboldt-log-test",
                r.resource().attributes().get(AttributeKey.stringKey("service.name")));
        assertTrue(r.observedEpochNanos() > 0L, "observed timestamp must be set");
    }

    @Test
    void emit_captures_current_span_context() {
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        SpanContext ctx = SpanContext.create(
                "0123456789abcdef0123456789abcdef", "0123456789abcdef",
                TraceFlags.getSampled(), TraceState.getDefault());

        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            try (Scope ignored = Context.root().with(Span.wrap(ctx)).makeCurrent()) {
                p.get("x").logRecordBuilder()
                        .setBody("in-trace")
                        .emit();
            }
        }
        LogRecordData r = exporter.getCollected().getFirst();
        assertEquals(ctx.getTraceId(), r.spanContext().getTraceId());
        assertEquals(ctx.getSpanId(), r.spanContext().getSpanId());
        assertTrue(r.spanContext().isSampled());
    }

    @Test
    void severity_text_and_number_independently_settable() {
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            p.get("x").logRecordBuilder()
                    .setSeverity(Severity.ERROR)
                    .setSeverityText("MY_ERROR")
                    .setBody("boom")
                    .emit();
        }
        LogRecordData r = exporter.getCollected().getFirst();
        assertEquals(Severity.ERROR, r.severity());
        assertEquals("MY_ERROR", r.severityText());
    }

    @Test
    void logger_cache_returns_same_instance_for_same_scope() {
        try (SdkLoggerProvider p = SdkLoggerProvider.builder().build()) {
            assertSame(p.get("scope-a"), p.get("scope-a"));
            assertNotEquals(p.get("scope-a"), p.get("scope-b"));
        }
    }

    @Test
    void batch_processor_drains_on_shutdown() {
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        BatchLogRecordProcessor batch = BatchLogRecordProcessor.builder(exporter)
                .setScheduleDelay(Duration.ofSeconds(60))
                .setMaxExportBatchSize(100)
                .build();
        SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(batch)
                .build();
        Logger l = p.get("x");
        for (int i = 0; i < 5; i++) {
            l.logRecordBuilder().setBody("msg-" + i).emit();
        }
        // At this point, no flush has happened yet
        assertTrue(exporter.getCollected().size() <= 5);
        p.close();
        assertEquals(5, exporter.getCollected().size(),
                "shutdown must drain the remaining queue");
    }

    @Test
    void simple_processor_emits_synchronously() {
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            p.get("x").logRecordBuilder().setBody("sync").emit();
            assertEquals(1, exporter.getCollected().size(),
                    "SimpleLogRecordProcessor must export synchronously, without waiting");
        }
    }

    @Test
    void timestamp_unit_conversion_works() {
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            p.get("x").logRecordBuilder()
                    .setTimestamp(1_000L, TimeUnit.MILLISECONDS)
                    .setObservedTimestamp(2_000L, TimeUnit.MILLISECONDS)
                    .setBody("tx")
                    .emit();
        }
        LogRecordData r = exporter.getCollected().getFirst();
        assertEquals(1_000_000_000L, r.timestampEpochNanos());
        assertEquals(2_000_000_000L, r.observedEpochNanos());
    }
}
