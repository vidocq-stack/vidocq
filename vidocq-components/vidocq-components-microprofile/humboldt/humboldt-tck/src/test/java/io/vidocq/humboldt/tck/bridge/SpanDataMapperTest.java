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
package io.vidocq.humboldt.tck.bridge;

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
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class SpanDataMapperTest {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String SPAN_ID = "fedcba9876543210";
    private static final String PARENT_SPAN_ID = "1111222233334444";

    @Test
    public void converts_minimal_span() {
        SpanData src = newBuilder()
                .name("minimal")
                .kind(SpanKind.INTERNAL)
                .build();

        io.opentelemetry.sdk.trace.data.SpanData out = SpanDataMapper.toOtel(src);

        assertEquals(out.getName(), "minimal");
        assertEquals(out.getKind(), SpanKind.INTERNAL);
        assertEquals(out.getTraceId(), TRACE_ID);
        assertEquals(out.getSpanId(), SPAN_ID);
        assertEquals(out.getStartEpochNanos(), 1_000L);
        assertEquals(out.getEndEpochNanos(), 2_000L);
        assertTrue(out.hasEnded());
        assertEquals(out.getEvents().size(), 0);
        assertEquals(out.getLinks().size(), 0);
        assertEquals(out.getStatus().getStatusCode(), StatusCode.UNSET);
    }

    @Test
    public void converts_parent_span_context() {
        SpanContext parent = SpanContext.create(TRACE_ID, PARENT_SPAN_ID,
                TraceFlags.getDefault(), TraceState.getDefault());
        SpanData src = newBuilder()
                .name("with-parent")
                .parent(parent)
                .build();

        io.opentelemetry.sdk.trace.data.SpanData out = SpanDataMapper.toOtel(src);

        assertEquals(out.getParentSpanId(), PARENT_SPAN_ID);
        assertEquals(out.getParentSpanContext().getTraceId(), TRACE_ID);
    }

    @Test
    public void parent_invalid_when_null_in_source() {
        SpanData src = newBuilder().name("no-parent").parent(null).build();

        io.opentelemetry.sdk.trace.data.SpanData out = SpanDataMapper.toOtel(src);

        assertEquals(out.getParentSpanContext(), SpanContext.getInvalid());
    }

    @Test
    public void converts_attributes() {
        Attributes attrs = Attributes.builder()
                .put(AttributeKey.stringKey("http.method"), "GET")
                .put(AttributeKey.longKey("http.status_code"), 200L)
                .build();
        SpanData src = newBuilder().name("attrs").attributes(attrs).build();

        io.opentelemetry.sdk.trace.data.SpanData out = SpanDataMapper.toOtel(src);

        assertEquals(out.getAttributes().get(AttributeKey.stringKey("http.method")), "GET");
        assertEquals(out.getAttributes().get(AttributeKey.longKey("http.status_code")), Long.valueOf(200L));
        assertEquals(out.getTotalAttributeCount(), 2);
    }

    @Test
    public void converts_events() {
        EventData evt = new EventData(5_000L, "boom",
                Attributes.of(AttributeKey.stringKey("exception.type"), "RuntimeException"));
        SpanData src = newBuilder().name("with-events").events(List.of(evt)).build();

        io.opentelemetry.sdk.trace.data.SpanData out = SpanDataMapper.toOtel(src);

        assertEquals(out.getEvents().size(), 1);
        var first = out.getEvents().getFirst();
        assertEquals(first.getName(), "boom");
        assertEquals(first.getEpochNanos(), 5_000L);
        assertEquals(first.getAttributes().get(AttributeKey.stringKey("exception.type")),
                "RuntimeException");
        assertEquals(out.getTotalRecordedEvents(), 1);
    }

    @Test
    public void converts_links() {
        SpanContext otherTrace = SpanContext.create(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb",
                TraceFlags.getDefault(), TraceState.getDefault());
        LinkData link = new LinkData(otherTrace,
                Attributes.of(AttributeKey.stringKey("link.kind"), "follows-from"));
        SpanData src = newBuilder().name("with-links").links(List.of(link)).build();

        io.opentelemetry.sdk.trace.data.SpanData out = SpanDataMapper.toOtel(src);

        assertEquals(out.getLinks().size(), 1);
        var first = out.getLinks().getFirst();
        assertEquals(first.getSpanContext().getTraceId(), "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertEquals(first.getAttributes().get(AttributeKey.stringKey("link.kind")), "follows-from");
        assertEquals(out.getTotalRecordedLinks(), 1);
    }

    @Test
    public void converts_status_error_with_description() {
        SpanData src = newBuilder()
                .name("error")
                .status(StatusData.error("RuntimeException: bang"))
                .build();

        io.opentelemetry.sdk.trace.data.SpanData out = SpanDataMapper.toOtel(src);

        assertEquals(out.getStatus().getStatusCode(), StatusCode.ERROR);
        assertEquals(out.getStatus().getDescription(), "RuntimeException: bang");
    }

    @Test
    public void converts_resource_with_attributes() {
        Attributes resourceAttrs = Attributes.of(
                AttributeKey.stringKey("service.name"), "humboldt-tck-test");
        SpanData src = newBuilder()
                .name("with-resource")
                .resource(Resource.create(resourceAttrs))
                .build();

        io.opentelemetry.sdk.trace.data.SpanData out = SpanDataMapper.toOtel(src);

        assertEquals(out.getResource().getAttribute(AttributeKey.stringKey("service.name")),
                "humboldt-tck-test");
    }

    @Test
    public void converts_instrumentation_scope() {
        SpanData src = newBuilder()
                .name("with-scope")
                .scope(new InstrumentationScope("io.vidocq.test", "1.2.3", null, Attributes.empty()))
                .build();

        io.opentelemetry.sdk.trace.data.SpanData out = SpanDataMapper.toOtel(src);

        assertNotNull(out.getInstrumentationScopeInfo());
        assertEquals(out.getInstrumentationScopeInfo().getName(), "io.vidocq.test");
        assertEquals(out.getInstrumentationScopeInfo().getVersion(), "1.2.3");
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
