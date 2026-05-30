package io.vidocq.humboldt.tck.bridge;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;

import java.util.List;

/**
 * Converts a {@link io.vidocq.humboldt.sdk.trace.data.SpanData Humboldt SpanData}
 * to the {@link SpanData OTel SDK SpanData} format expected by the exporters
 * provided by the MicroProfile Telemetry 2.1 TCK.
 *
 * <p>Confined to the TCK runner (never used in prod) — this is the adaptation layer
 * that lets Humboldt pass the TCK without embedding the OTel SDK in
 * its application runtime (see <code>tasks/m7b-architecture-analysis.md</code>
 * option C).</p>
 *
 * <p>The public OTel API (Attributes, SpanContext, SpanKind, StatusCode) is
 * shared between the two SDKs: only the <code>io.opentelemetry.sdk.*</code> types
 * (Resource, EventData, LinkData, StatusData, InstrumentationScopeInfo) need
 * to be translated.</p>
 */
public final class SpanDataMapper {

    private SpanDataMapper() {}

    public static SpanData toOtel(io.vidocq.humboldt.sdk.trace.data.SpanData src) {
        SpanContext parent = src.parentSpanContext() != null
                ? src.parentSpanContext()
                : SpanContext.getInvalid();

        List<EventData> events = src.events().stream()
                .map(e -> EventData.create(e.epochNanos(), e.name(), e.attributes()))
                .toList();

        List<LinkData> links = src.links().stream()
                .map(l -> LinkData.create(l.spanContext(), l.attributes()))
                .toList();

        StatusData status = StatusData.create(src.status().code(), src.status().description());

        Resource resource = Resource.create(src.resource().attributes());

        InstrumentationScope scope = src.instrumentationScope();
        InstrumentationScopeInfo scopeInfo = scope.version() != null
                ? InstrumentationScopeInfo.create(scope.name(), scope.version(), scope.schemaUrl())
                : InstrumentationScopeInfo.create(scope.name());

        return TestSpanData.builder()
                .setSpanContext(src.spanContext())
                .setParentSpanContext(parent)
                .setName(src.name())
                .setKind(src.kind())
                .setStartEpochNanos(src.startEpochNanos())
                .setEndEpochNanos(src.endEpochNanos())
                .setAttributes(src.attributes())
                .setEvents(events)
                .setLinks(links)
                .setStatus(status)
                .setResource(resource)
                .setInstrumentationScopeInfo(scopeInfo)
                .setHasEnded(src.hasEnded())
                .setTotalRecordedEvents(events.size())
                .setTotalRecordedLinks(links.size())
                .setTotalAttributeCount(src.attributes().size())
                .build();
    }
}
