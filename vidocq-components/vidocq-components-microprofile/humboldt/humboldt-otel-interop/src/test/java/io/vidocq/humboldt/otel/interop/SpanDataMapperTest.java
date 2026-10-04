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
package io.vidocq.humboldt.otel.interop;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.trace.data.EventData;
import io.vidocq.humboldt.sdk.trace.data.LinkData;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.data.StatusData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpanDataMapperTest {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String SPAN_ID = "fedcba9876543210";
    private static final String PARENT_SPAN_ID = "1111222233334444";

    @Test
    void converts_minimal_span() {
        SpanData src = newBuilder()
                .name("minimal")
                .kind(SpanKind.INTERNAL)
                .build();

        io.opentelemetry.sdk.trace.data.SpanData out = SpanDataMapper.toOtel(src);

        assertEquals("minimal", out.getName());
        assertEquals(SpanKind.INTERNAL, out.getKind());
        assertEquals(TRACE_ID, out.getTraceId());
        assertEquals(SPAN_ID, out.getSpanId());
        assertEquals(1_000L, out.getStartEpochNanos());
        assertEquals(2_000L, out.getEndEpochNanos());
        assertTrue(out.hasEnded());
        assertEquals(0, out.getEvents().size());
        assertEquals(0, out.getLinks().size());
        assertEquals(StatusCode.UNSET, out.getStatus().getStatusCode());
    }

    @Test
    void converts_parent_span_context() {
        SpanContext parent = SpanContext.create(TRACE_ID, PARENT_SPAN_ID,
                TraceFlags.getDefault(), TraceState.getDefault());
        SpanData src = newBuilder()
                .name("with-parent")
                .parent(parent)
                .build();

        io.opentelemetry.sdk.trace.data.SpanData out = SpanDataMapper.toOtel(src);

        assertEquals(PARENT_SPAN_ID, out.getParentSpanId());
        assertEquals(TRACE_ID, out.getParentSpanContext().getTraceId());
    }

    @Test
    void parent_invalid_when_null_in_source() {
        SpanData src = newBuilder().name("no-parent").parent(null).build();

        io.opentelemetry.sdk.trace.data.SpanData out = SpanDataMapper.toOtel(src);

        assertEquals(SpanContext.getInvalid(), out.getParentSpanContext());
    }

    @Test
    void converts_attributes() {
        Attributes attrs = Attributes.builder()
                .put(AttributeKey.stringKey("http.method"), "GET")
                .put(AttributeKey.longKey("http.status_code"), 200L)
                .build();
        SpanData src = newBuilder().name("attrs").attributes(attrs).build();

        io.opentelemetry.sdk.trace.data.SpanData out = SpanDataMapper.toOtel(src);

        assertEquals("GET", out.getAttributes().get(AttributeKey.stringKey("http.method")));
        assertEquals(Long.valueOf(200L), out.getAttributes().get(AttributeKey.longKey("http.status_code")));
        assertEquals(2, out.getTotalAttributeCount());
    }

    @Test
    void converts_events() {
        EventData evt = new EventData(5_000L, "boom",
                Attributes.of(AttributeKey.stringKey("exception.type"), "RuntimeException"));
        SpanData src = newBuilder().name("with-events").events(List.of(evt)).build();

        io.opentelemetry.sdk.trace.data.SpanData out = SpanDataMapper.toOtel(src);

        assertEquals(1, out.getEvents().size());
        var first = out.getEvents().getFirst();
        assertEquals("boom", first.getName());
        assertEquals(5_000L, first.getEpochNanos());
        assertEquals("RuntimeException",
                first.getAttributes().get(AttributeKey.stringKey("exception.type")));
        assertEquals(1, out.getTotalRecordedEvents());
    }

    @Test
    void converts_links() {
        SpanContext otherTrace = SpanContext.create(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb",
                TraceFlags.getDefault(), TraceState.getDefault());
        LinkData link = new LinkData(otherTrace,
                Attributes.of(AttributeKey.stringKey("link.kind"), "follows-from"));
        SpanData src = newBuilder().name("with-links").links(List.of(link)).build();

        io.opentelemetry.sdk.trace.data.SpanData out = SpanDataMapper.toOtel(src);

        assertEquals(1, out.getLinks().size());
        var first = out.getLinks().getFirst();
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", first.getSpanContext().getTraceId());
        assertEquals("follows-from", first.getAttributes().get(AttributeKey.stringKey("link.kind")));
        assertEquals(1, out.getTotalRecordedLinks());
    }

    @Test
    void converts_status_error_with_description() {
        SpanData src = newBuilder()
                .name("error")
                .status(StatusData.error("RuntimeException: bang"))
                .build();

        io.opentelemetry.sdk.trace.data.SpanData out = SpanDataMapper.toOtel(src);

        assertEquals(StatusCode.ERROR, out.getStatus().getStatusCode());
        assertEquals("RuntimeException: bang", out.getStatus().getDescription());
    }

    @Test
    void converts_resource_with_attributes() {
        Attributes resourceAttrs = Attributes.of(
                AttributeKey.stringKey("service.name"), "humboldt-tck-test");
        SpanData src = newBuilder()
                .name("with-resource")
                .resource(Resource.create(resourceAttrs))
                .build();

        io.opentelemetry.sdk.trace.data.SpanData out = SpanDataMapper.toOtel(src);

        assertEquals("humboldt-tck-test",
                out.getResource().getAttribute(AttributeKey.stringKey("service.name")));
    }

    @Test
    void converts_instrumentation_scope() {
        SpanData src = newBuilder()
                .name("with-scope")
                .scope(new InstrumentationScope("io.vidocq.test", "1.2.3", null, Attributes.empty()))
                .build();

        io.opentelemetry.sdk.trace.data.SpanData out = SpanDataMapper.toOtel(src);

        assertNotNull(out.getInstrumentationScopeInfo());
        assertEquals("io.vidocq.test", out.getInstrumentationScopeInfo().getName());
        assertEquals("1.2.3", out.getInstrumentationScopeInfo().getVersion());
    }

    private static TestBuilder newBuilder() {
        return new TestBuilder();
    }

    private static final class TestBuilder {
        private SpanContext spanContext = SpanContext.create(TRACE_ID, SPAN_ID,
                TraceFlags.getSampled(), TraceState.getDefault());
        private SpanContext parent = SpanContext.getInvalid();
        private String name = "test";
        private SpanKind kind = SpanKind.INTERNAL;
        private long startNanos = 1_000L;
        private long endNanos = 2_000L;
        private Attributes attributes = Attributes.empty();
        private List<EventData> events = List.of();
        private List<LinkData> links = List.of();
        private StatusData status = StatusData.unset();
        private Resource resource = Resource.empty();
        private InstrumentationScope scope = InstrumentationScope.of("io.vidocq.test");

        TestBuilder name(String n) { this.name = n; return this; }
        TestBuilder kind(SpanKind k) { this.kind = k; return this; }
        TestBuilder parent(SpanContext p) { this.parent = p; return this; }
        TestBuilder attributes(Attributes a) { this.attributes = a; return this; }
        TestBuilder events(List<EventData> e) { this.events = e; return this; }
        TestBuilder links(List<LinkData> l) { this.links = l; return this; }
        TestBuilder status(StatusData s) { this.status = s; return this; }
        TestBuilder resource(Resource r) { this.resource = r; return this; }
        TestBuilder scope(InstrumentationScope s) { this.scope = s; return this; }

        SpanData build() {
            return new SpanData(spanContext, parent, name, kind, startNanos, endNanos,
                    attributes, events, links, status, resource, scope);
        }
    }
}
