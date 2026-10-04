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
import io.opentelemetry.api.common.KeyValue;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.common.ValueType;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void setException_records_type_message_and_stacktrace_attributes() {
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            p.get("x").logRecordBuilder()
                    .setBody("payment failed")
                    .setException(new IllegalStateException("boom"))
                    .emit();
        }
        Attributes attrs = exporter.getCollected().getFirst().attributes();
        assertEquals("java.lang.IllegalStateException", attrs.get(EXCEPTION_TYPE));
        assertEquals("boom", attrs.get(EXCEPTION_MESSAGE));
        String stacktrace = attrs.get(EXCEPTION_STACKTRACE);
        assertNotNull(stacktrace, "exception.stacktrace must be set");
        assertTrue(stacktrace.startsWith("java.lang.IllegalStateException: boom"), stacktrace);
        assertTrue(stacktrace.contains("setException_records_type_message_and_stacktrace_attributes"),
                "the stack trace must list the throwing frame: " + stacktrace);
    }

    @Test
    void setException_keeps_exception_attributes_already_set() {
        // Same rule as the OpenTelemetry SDK: an attribute set by the caller wins over the derived one.
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            p.get("x").logRecordBuilder()
                    .setAttribute(EXCEPTION_TYPE, "com.example.PaymentRefused")
                    .setException(new IllegalStateException("boom"))
                    .emit();
        }
        Attributes attrs = exporter.getCollected().getFirst().attributes();
        assertEquals("com.example.PaymentRefused", attrs.get(EXCEPTION_TYPE));
        assertEquals("boom", attrs.get(EXCEPTION_MESSAGE));
        assertNotNull(attrs.get(EXCEPTION_STACKTRACE));
    }

    @Test
    void setException_uses_the_canonical_class_name_and_skips_a_null_message() {
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            p.get("x").logRecordBuilder()
                    .setException(new NestedFailure())
                    .emit();
            p.get("x").logRecordBuilder()
                    .setException(null)
                    .emit();
        }
        Attributes nested = exporter.getCollected().getFirst().attributes();
        assertEquals(SdkLoggerProviderTest.class.getName() + ".NestedFailure", nested.get(EXCEPTION_TYPE),
                "canonical name ('.' before the nested class), as the OpenTelemetry SDK does");
        assertNull(nested.get(EXCEPTION_MESSAGE), "no message attribute for a null message");
        assertTrue(exporter.getCollected().get(1).attributes().isEmpty(), "setException(null) is a no-op");
    }

    @Test
    void setException_falls_back_to_the_binary_name_without_a_canonical_name() {
        // Same rule as Span.recordException: an anonymous class has no canonical name, so exception.type
        // falls back to the binary name instead of being dropped.
        RuntimeException anonymous = new RuntimeException("anonymous") {};
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            p.get("x").logRecordBuilder()
                    .setException(anonymous)
                    .emit();
        }
        assertNull(anonymous.getClass().getCanonicalName(), "an anonymous class has no canonical name");
        assertEquals(anonymous.getClass().getName(),
                exporter.getCollected().getFirst().attributes().get(EXCEPTION_TYPE));
    }

    @Test
    void setBody_value_keeps_the_structured_body() {
        Value<?> body = Value.of(
                KeyValue.of("user", Value.of("alice")),
                KeyValue.of("count", Value.of(3L)));
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            p.get("x").logRecordBuilder()
                    .setBody(body)
                    .emit();
        }
        LogRecordData r = exporter.getCollected().getFirst();
        assertEquals(body, r.bodyValue(), "the structured body is kept, not flattened to a string");
        assertEquals(body.asString(), r.body(), "body() is the string form of the structured body");
    }

    @Test
    void setBody_string_is_also_exposed_as_a_string_value() {
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            p.get("x").logRecordBuilder().setBody("plain").emit();
            p.get("x").logRecordBuilder().emit();
            p.get("x").logRecordBuilder().setBody(Value.of(KeyValue.of("k", Value.of("v")))).setBody("last wins").emit();
        }
        List<LogRecordData> records = exporter.getCollected();
        assertEquals(Value.of("plain"), records.get(0).bodyValue());
        assertEquals("plain", records.get(0).body());
        assertNull(records.get(1).bodyValue(), "no body set: no body value");
        assertEquals("", records.get(1).body());
        assertEquals(Value.of("last wins"), records.get(2).bodyValue());
    }

    @Test
    void an_explicitly_set_empty_string_body_is_kept_whether_set_as_a_string_or_as_a_value() {
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            p.get("x").logRecordBuilder().setBody("").emit();
            p.get("x").logRecordBuilder().setBody(Value.of("")).emit();
            p.get("x").logRecordBuilder().setBody("earlier").setBody(Value.of("")).emit();
        }
        for (LogRecordData r : exporter.getCollected()) {
            assertEquals(Value.of(""), r.bodyValue(), "an empty string body set explicitly is a body (OTel 1.66)");
            assertEquals("", r.body());
        }
        LogRecordData direct = new LogRecordData(null, null, 0L, 0L, SpanContext.getInvalid(),
                null, null, "", null, "", Value.of(""));
        assertEquals(Value.of(""), direct.bodyValue(), "the record keeps it when built directly");
        LogRecordData stringOnly = new LogRecordData(null, null, 0L, 0L, SpanContext.getInvalid(),
                null, null, "", null, "");
        assertNull(stringOnly.bodyValue(), "an empty string without a body value still means no body");
    }

    @Test
    void a_structured_body_is_rendered_as_a_string_once_per_record() {
        CountingValue body = new CountingValue(Value.of(KeyValue.of("user", Value.of("alice"))));
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            p.get("x").logRecordBuilder().setBody(body).emit();
        }
        assertEquals("{\"user\":\"alice\"}", exporter.getCollected().getFirst().body());
        assertEquals(1, body.asStringCalls, "Value.asString() calls for one emitted record");
    }

    /** A structured body that counts how many times its string form is computed. */
    private static final class CountingValue implements Value<List<KeyValue>> {
        private final Value<List<KeyValue>> delegate;
        private int asStringCalls;

        CountingValue(Value<List<KeyValue>> delegate) {
            this.delegate = delegate;
        }

        @Override
        public ValueType getType() {
            return delegate.getType();
        }

        @Override
        public List<KeyValue> getValue() {
            return delegate.getValue();
        }

        @Override
        public String asString() {
            asStringCalls++;
            return delegate.asString();
        }
    }

    @Test
    void setEventName_is_carried_to_the_exported_record() {
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            p.get("x").logRecordBuilder()
                    .setEventName("checkout.completed")
                    .setBody("done")
                    .emit();
            p.get("x").logRecordBuilder()
                    .setBody("plain log")
                    .emit();
        }
        assertEquals("checkout.completed", exporter.getCollected().getFirst().eventName());
        assertEquals("", exporter.getCollected().get(1).eventName(), "a plain log record has no event name");
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

    private static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message");
    private static final AttributeKey<String> EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace");

    /** Nested exception without a message — its canonical and binary names differ. */
    static final class NestedFailure extends RuntimeException {
    }
}
