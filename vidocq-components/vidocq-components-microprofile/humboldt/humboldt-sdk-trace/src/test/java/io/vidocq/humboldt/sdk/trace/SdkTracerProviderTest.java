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
import io.opentelemetry.context.Scope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.export.InMemorySpanExporter;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkTracerProviderTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder()
                .setResource(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "humboldt-test")))
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracer = provider.get("humboldt.test");
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    void simple_span_is_captured() {
        Span s = tracer.spanBuilder("hello").startSpan();
        s.setAttribute("k", "v").end();

        List<SpanData> spans = exporter.getFinishedSpans();
        assertEquals(1, spans.size());
        SpanData sd = spans.getFirst();
        assertEquals("hello", sd.name());
        assertEquals("v", sd.attributes().get(AttributeKey.stringKey("k")));
        assertEquals(SpanKind.INTERNAL, sd.kind());
        assertTrue(sd.hasEnded());
        assertTrue(sd.endEpochNanos() >= sd.startEpochNanos());
    }

    @Test
    void resource_is_propagated_to_span_data() {
        Span s = tracer.spanBuilder("resource-test").startSpan();
        s.end();
        SpanData sd = exporter.getFinishedSpans().getFirst();
        assertEquals("humboldt-test",
                sd.resource().attributes().get(AttributeKey.stringKey("service.name")));
    }

    @Test
    void parent_child_relationship_via_context() {
        Span parent = tracer.spanBuilder("parent").startSpan();
        try (Scope sc = parent.makeCurrent()) {
            Span child = tracer.spanBuilder("child").startSpan();
            child.end();
        }
        parent.end();

        List<SpanData> spans = exporter.getFinishedSpans();
        assertEquals(2, spans.size());
        SpanData childSpan = spans.stream().filter(s -> s.name().equals("child")).findFirst().orElseThrow();
        SpanData parentSpan = spans.stream().filter(s -> s.name().equals("parent")).findFirst().orElseThrow();

        assertEquals(parentSpan.spanContext().getTraceId(), childSpan.spanContext().getTraceId(),
                "parent et child doivent partager le traceId");
        assertNotEquals(parentSpan.spanContext().getSpanId(), childSpan.spanContext().getSpanId(),
                "parent et child doivent avoir des spanId distincts");
        assertNotNull(childSpan.parentSpanContext());
        assertEquals(parentSpan.spanContext().getSpanId(), childSpan.parentSpanContext().getSpanId());
    }

    @Test
    void no_parent_starts_new_trace() {
        Span parent = tracer.spanBuilder("parent").startSpan();
        try (Scope sc = parent.makeCurrent()) {
            Span sibling = tracer.spanBuilder("orphan").setNoParent().startSpan();
            sibling.end();
        }
        parent.end();

        SpanData orphan = exporter.getFinishedSpans().stream()
                .filter(s -> s.name().equals("orphan")).findFirst().orElseThrow();
        SpanData parentSpan = exporter.getFinishedSpans().stream()
                .filter(s -> s.name().equals("parent")).findFirst().orElseThrow();

        assertNotEquals(orphan.spanContext().getTraceId(), parentSpan.spanContext().getTraceId(),
                "setNoParent() must create a new trace");
        assertNull(orphan.parentSpanContext());
    }

    @Test
    void span_links_are_recorded() {
        SpanContext linkTarget = SpanContext.create(
                "11111111111111111111111111111111", "2222222222222222",
                TraceFlags.getSampled(), TraceState.getDefault());

        Span s = tracer.spanBuilder("linked")
                .addLink(linkTarget, Attributes.of(AttributeKey.stringKey("rel"), "follow"))
                .startSpan();
        s.end();

        SpanData sd = exporter.getFinishedSpans().getFirst();
        assertEquals(1, sd.links().size());
        assertEquals(linkTarget, sd.links().getFirst().spanContext());
        assertEquals("follow", sd.links().getFirst().attributes().get(AttributeKey.stringKey("rel")));
    }

    @Test
    void span_kind_and_status_are_captured() {
        Span s = tracer.spanBuilder("server-call").setSpanKind(SpanKind.SERVER).startSpan();
        s.setStatus(StatusCode.ERROR, "boom").end();

        SpanData sd = exporter.getFinishedSpans().getFirst();
        assertEquals(SpanKind.SERVER, sd.kind());
        assertEquals(StatusCode.ERROR, sd.status().code());
        assertEquals("boom", sd.status().description());
    }

    @Test
    void events_are_recorded() {
        Span s = tracer.spanBuilder("with-events").startSpan();
        s.addEvent("step-1");
        s.addEvent("step-2", Attributes.of(AttributeKey.longKey("retries"), 3L));
        s.end();

        SpanData sd = exporter.getFinishedSpans().getFirst();
        assertEquals(2, sd.events().size());
        assertEquals("step-1", sd.events().get(0).name());
        assertEquals(3L, sd.events().get(1).attributes().get(AttributeKey.longKey("retries")));
    }

    @Test
    void recordException_attaches_exception_event() {
        Span s = tracer.spanBuilder("might-throw").startSpan();
        s.recordException(new IllegalStateException("nope"));
        s.end();

        SpanData sd = exporter.getFinishedSpans().getFirst();
        assertEquals(1, sd.events().size());
        var ev = sd.events().getFirst();
        assertEquals("exception", ev.name());
        assertEquals("java.lang.IllegalStateException",
                ev.attributes().get(AttributeKey.stringKey("exception.type")));
        assertEquals("nope",
                ev.attributes().get(AttributeKey.stringKey("exception.message")));
    }

    @Test
    void tracer_cache_returns_same_instance_for_same_scope_name() {
        assertSame(provider.get("scope-a"), provider.get("scope-a"));
        assertNotEquals(provider.get("scope-a"), provider.get("scope-b"));
    }
}
