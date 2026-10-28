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
package io.vidocq.humboldt.sdk.trace;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.trace.data.EventData;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.export.InMemorySpanExporter;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Span and span builder behaviour, observed through the exported {@link SpanData}. */
class SdkSpanTest {

    private static final String SPAN = "op";
    private static final String KEY = "k";
    private static final String LATE = "late";
    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String PARENT_SPAN_ID = "b7ad6b7169203331";

    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracer = provider.get("humboldt.span.test");
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    private SpanData only() {
        List<SpanData> spans = exporter.getFinishedSpans();
        assertEquals(1, spans.size());
        return spans.getFirst();
    }

    @Test
    void invalid_attributes_are_ignored() {
        Span span = tracer.spanBuilder(SPAN).startSpan();
        span.setAttribute(AttributeKey.stringKey(""), "empty-key");
        span.setAttribute((AttributeKey<String>) null, "null-key");
        span.setAttribute(AttributeKey.stringKey(KEY), null);
        span.setAttribute(KEY, "v");
        span.end();
        assertEquals(Attributes.of(AttributeKey.stringKey(KEY), "v"), only().attributes());
    }

    @Test
    void ended_span_ignores_every_mutation() {
        Span span = tracer.spanBuilder(SPAN).startSpan();
        assertTrue(span.isRecording());
        span.end();
        assertFalse(span.isRecording());

        span.setAttribute(KEY, LATE);
        span.addEvent(LATE);
        span.setStatus(StatusCode.ERROR, LATE);
        span.recordException(new IllegalStateException(LATE));
        span.updateName(LATE);
        span.end();

        SpanData data = only();
        assertEquals(SPAN, data.name());
        assertTrue(data.attributes().isEmpty());
        assertTrue(data.events().isEmpty());
        assertEquals(StatusCode.UNSET, data.status().code());
    }

    @Test
    void events_carry_explicit_timestamps_and_attributes() {
        Attributes attrs = Attributes.of(AttributeKey.longKey("n"), 1L);
        Span span = tracer.spanBuilder(SPAN).startSpan();
        span.addEvent("plain");
        span.addEvent("timed", 5, TimeUnit.SECONDS);
        span.addEvent("with-attrs", attrs);
        span.addEvent("timed-with-attrs", attrs, 7, TimeUnit.MILLISECONDS);
        span.addEvent(null);
        span.end();

        List<EventData> events = only().events();
        assertEquals(4, events.size());
        assertEquals("plain", events.get(0).name());
        assertEquals(TimeUnit.SECONDS.toNanos(5), events.get(1).epochNanos());
        assertEquals(attrs, events.get(2).attributes());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(7), events.get(3).epochNanos());
        assertEquals(attrs, events.get(3).attributes());
    }

    @Test
    void error_status_keeps_its_description_and_ok_is_final() {
        Span span = tracer.spanBuilder(SPAN).startSpan();
        span.setStatus(StatusCode.ERROR, "boom");
        span.setStatus(null, "ignored");
        Span okSpan = tracer.spanBuilder("ok").startSpan();
        okSpan.setStatus(StatusCode.OK);
        okSpan.setStatus(StatusCode.ERROR, "too late");
        Span unsetSpan = tracer.spanBuilder("unset").startSpan();
        unsetSpan.setStatus(StatusCode.ERROR);
        unsetSpan.setStatus(StatusCode.UNSET);
        span.end();
        okSpan.end();
        unsetSpan.end();

        List<SpanData> spans = exporter.getFinishedSpans();
        assertEquals(StatusCode.ERROR, spans.get(0).status().code());
        assertEquals("boom", spans.get(0).status().description());
        assertEquals(StatusCode.OK, spans.get(1).status().code());
        assertEquals(StatusCode.UNSET, spans.get(2).status().code());
    }

    @Test
    void error_status_without_description_has_an_empty_one() {
        tracer.spanBuilder(SPAN).startSpan().setStatus(StatusCode.ERROR, null).end();
        assertEquals("", only().status().description());
    }

    @Test
    void null_exception_and_null_name_are_ignored() {
        Span span = tracer.spanBuilder(SPAN).startSpan();
        span.recordException(null);
        span.updateName(null);
        span.end();
        SpanData data = only();
        assertEquals(SPAN, data.name());
        assertTrue(data.events().isEmpty());
    }

    @Test
    void exception_without_message_has_no_message_attribute() {
        Span span = tracer.spanBuilder(SPAN).startSpan();
        span.recordException(new IllegalStateException(), null);
        span.end();
        Attributes attrs = only().events().getFirst().attributes();
        assertNull(attrs.get(AttributeKey.stringKey("exception.message")));
        assertEquals(IllegalStateException.class.getName(), attrs.get(AttributeKey.stringKey("exception.type")));
    }

    @Test
    void updateName_and_explicit_end_timestamp_are_exported() {
        Span span = tracer.spanBuilder(SPAN)
                .setStartTimestamp(1, TimeUnit.SECONDS)
                .startSpan();
        span.updateName("renamed");
        span.end(3, TimeUnit.SECONDS);
        span.end(9, TimeUnit.SECONDS);

        SpanData data = only();
        assertEquals("renamed", data.name());
        assertEquals(TimeUnit.SECONDS.toNanos(1), data.startEpochNanos());
        assertEquals(TimeUnit.SECONDS.toNanos(3), data.endEpochNanos());
        assertTrue(data.hasEnded());
    }

    @Test
    void builder_typed_attributes_and_kind_are_recorded() {
        tracer.spanBuilder(SPAN)
                .setAttribute("l", 2L)
                .setAttribute("d", 1.5)
                .setAttribute("b", true)
                .setAttribute(AttributeKey.stringKey(""), "ignored")
                .setAttribute(AttributeKey.stringKey(KEY), null)
                .setSpanKind(SpanKind.PRODUCER)
                .setSpanKind(null)
                .setStartTimestamp(-1, TimeUnit.SECONDS)
                .setStartTimestamp(1, null)
                .startSpan()
                .end();

        SpanData data = only();
        assertEquals(SpanKind.PRODUCER, data.kind());
        assertEquals(3, data.attributes().size());
        assertEquals(2L, data.attributes().get(AttributeKey.longKey("l")));
        assertEquals(1.5, data.attributes().get(AttributeKey.doubleKey("d")));
        assertEquals(Boolean.TRUE, data.attributes().get(AttributeKey.booleanKey("b")));
        assertTrue(data.startEpochNanos() > 0);
    }

    @Test
    void invalid_links_are_dropped() {
        SpanContext valid = SpanContext.create(TRACE_ID, PARENT_SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());
        tracer.spanBuilder(SPAN)
                .addLink(SpanContext.getInvalid())
                .addLink(null)
                .addLink(valid, null)
                .startSpan()
                .end();
        SpanData data = only();
        assertEquals(1, data.links().size());
        assertEquals(valid, data.links().getFirst().spanContext());
        assertTrue(data.links().getFirst().attributes().isEmpty());
    }

    @Test
    void setNoParent_ignores_the_current_span() {
        Span parent = tracer.spanBuilder("parent").startSpan();
        try (var _ = parent.makeCurrent()) {
            tracer.spanBuilder(SPAN).setNoParent().startSpan().end();
        }
        parent.end();
        SpanData child = exporter.getFinishedSpans().getFirst();
        assertNull(child.parentSpanContext());
        assertFalse(child.spanContext().getTraceId().equals(parent.getSpanContext().getTraceId()));
    }

    @Test
    void parent_based_sampler_follows_a_remote_parent() {
        SdkTracerProvider parentBased = SdkTracerProvider.builder()
                .setSampler(Sampler.parentBased(Sampler.alwaysOff()))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer t = parentBased.get("parent-based");
        SpanContext sampled = SpanContext.createFromRemoteParent(
                TRACE_ID, PARENT_SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());
        SpanContext notSampled = SpanContext.createFromRemoteParent(
                TRACE_ID, PARENT_SPAN_ID, TraceFlags.getDefault(), TraceState.getDefault());

        Span kept = t.spanBuilder("kept").setParent(Context.root().with(Span.wrap(sampled))).startSpan();
        Span dropped = t.spanBuilder("dropped").setParent(Context.root().with(Span.wrap(notSampled))).startSpan();
        Span root = t.spanBuilder("root").startSpan();
        kept.end();
        dropped.end();
        root.end();
        parentBased.close();

        assertTrue(kept.getSpanContext().isSampled());
        assertFalse(dropped.getSpanContext().isSampled());
        assertFalse(root.getSpanContext().isSampled());
        assertEquals(TRACE_ID, dropped.getSpanContext().getTraceId());
        SpanData exported = only();
        assertEquals("kept", exported.name());
        assertEquals(PARENT_SPAN_ID, exported.parentSpanContext().getSpanId());
    }

    @Test
    void provider_ignores_null_configuration_and_caches_by_scope_name() {
        SdkTracerProvider p = SdkTracerProvider.builder()
                .setResource(null)
                .setSampler(null)
                .setIdGenerator(null)
                .setClock(null)
                .addSpanProcessor(null)
                .build();
        assertTrue(p.getResource().attributes().isEmpty());
        assertTrue(p.getSpanProcessors().isEmpty());
        assertSame(p.get(null), p.get(""));
        assertSame(p.get("lib"), p.get("lib", "1.0"));
        assertTrue(p.flush().isSuccess());
        assertTrue(p.shutdown().isSuccess());
    }

    @Test
    void shutdown_stops_the_simple_processor() {
        assertTrue(provider.flush().isSuccess());
        assertTrue(provider.shutdown().isSuccess());
        assertTrue(provider.shutdown().isSuccess());
        tracer.spanBuilder(SPAN).startSpan().end();
        assertTrue(exporter.getFinishedSpans().isEmpty());
    }
}
