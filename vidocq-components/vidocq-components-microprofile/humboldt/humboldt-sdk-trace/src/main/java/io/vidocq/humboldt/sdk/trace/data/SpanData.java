package io.vidocq.humboldt.sdk.trace.data;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;

import java.util.List;

/**
 * Immutable view of a completed span, consumed by {@code SpanExporter}.
 *
 * <p>Frozen picture of the span state at {@code end()} time — neither the SDK nor
 * an exporter should mutate its contents after creation.</p>
 */
public record SpanData(
        SpanContext spanContext,
        SpanContext parentSpanContext,
        String name,
        SpanKind kind,
        long startEpochNanos,
        long endEpochNanos,
        Attributes attributes,
        List<EventData> events,
        List<LinkData> links,
        StatusData status,
        Resource resource,
        InstrumentationScope instrumentationScope) {

    public SpanData {
        if (spanContext == null) throw new NullPointerException("spanContext");
        if (name == null) throw new NullPointerException("name");
        if (kind == null) kind = SpanKind.INTERNAL;
        if (attributes == null) attributes = Attributes.empty();
        events = events == null ? List.of() : List.copyOf(events);
        links = links == null ? List.of() : List.copyOf(links);
        if (status == null) status = StatusData.unset();
        if (resource == null) resource = Resource.empty();
        if (instrumentationScope == null) instrumentationScope = InstrumentationScope.of("");
    }

    public boolean hasEnded() {
        return endEpochNanos > 0L;
    }
}
