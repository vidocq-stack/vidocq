package io.vidocq.humboldt.sdk.trace.internal;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.vidocq.humboldt.sdk.common.Clock;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.trace.ReadableSpan;
import io.vidocq.humboldt.sdk.trace.data.EventData;
import io.vidocq.humboldt.sdk.trace.data.InstrumentationScope;
import io.vidocq.humboldt.sdk.trace.data.LinkData;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.data.StatusData;
import io.vidocq.humboldt.sdk.trace.export.SpanProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Implémentation interne de {@link Span} + {@link ReadableSpan}.
 * Mutable jusqu'à {@link #end()} (verrou {@code synchronized}), immutable ensuite.
 *
 * <p>Une fois {@code end()} appelé, toute mutation ultérieure est ignorée
 * silencieusement (alignement sur le comportement OTel de référence).</p>
 */
public final class SdkSpan implements Span, ReadableSpan {

    private final SpanContext context;
    private final SpanContext parentContext;
    private final SpanKind kind;
    private final long startEpochNanos;
    private final Resource resource;
    private final InstrumentationScope scope;
    private final Clock clock;
    private final List<LinkData> links;
    private final List<SpanProcessor> processors;

    private String name;
    private AttributesBuilder attributes;
    private List<EventData> events;
    private StatusData status = StatusData.unset();
    private long endEpochNanos = 0L;
    private boolean ended = false;

    SdkSpan(
            SpanContext context,
            SpanContext parentContext,
            String name,
            SpanKind kind,
            long startEpochNanos,
            Attributes initialAttributes,
            List<LinkData> links,
            Resource resource,
            InstrumentationScope scope,
            Clock clock,
            List<SpanProcessor> processors) {
        this.context = context;
        this.parentContext = parentContext;
        this.name = name;
        this.kind = kind;
        this.startEpochNanos = startEpochNanos;
        this.attributes = initialAttributes.toBuilder();
        this.links = links;
        this.resource = resource;
        this.scope = scope;
        this.clock = clock;
        this.processors = processors;
    }

    @Override
    public synchronized <T> Span setAttribute(AttributeKey<T> key, T value) {
        if (ended || key == null || key.getKey().isEmpty() || value == null) return this;
        attributes.put(key, value);
        return this;
    }

    @Override
    public Span addEvent(String name) {
        return addEventInternal(name, Attributes.empty(), clock.now());
    }

    @Override
    public Span addEvent(String name, long timestamp, TimeUnit unit) {
        return addEventInternal(name, Attributes.empty(), unit.toNanos(timestamp));
    }

    @Override
    public Span addEvent(String name, Attributes attributes) {
        return addEventInternal(name, attributes, clock.now());
    }

    @Override
    public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
        return addEventInternal(name, attributes, unit.toNanos(timestamp));
    }

    private synchronized Span addEventInternal(String name, Attributes attrs, long epochNanos) {
        if (ended || name == null) return this;
        if (events == null) events = new ArrayList<>();
        events.add(new EventData(epochNanos, name, attrs != null ? attrs : Attributes.empty()));
        return this;
    }

    @Override
    public synchronized Span setStatus(StatusCode statusCode, String description) {
        if (ended || statusCode == null) return this;
        // Règle OTel : on ne peut pas descendre depuis OK. ERROR > OK > UNSET.
        if (this.status.code() == StatusCode.OK) return this;
        if (statusCode == StatusCode.OK) {
            this.status = StatusData.ok();
        } else if (statusCode == StatusCode.ERROR) {
            this.status = StatusData.error(description != null ? description : "");
        } else {
            this.status = StatusData.unset();
        }
        return this;
    }

    @Override
    public synchronized Span recordException(Throwable exception, Attributes additionalAttributes) {
        if (ended || exception == null) return this;
        AttributesBuilder builder = additionalAttributes != null
                ? additionalAttributes.toBuilder()
                : Attributes.builder();
        builder.put(AttributeKey.stringKey("exception.type"), exception.getClass().getName());
        if (exception.getMessage() != null) {
            builder.put(AttributeKey.stringKey("exception.message"), exception.getMessage());
        }
        builder.put(AttributeKey.stringKey("exception.stacktrace"), stackTraceOf(exception));
        return addEventInternal("exception", builder.build(), clock.now());
    }

    private static String stackTraceOf(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    @Override
    public synchronized Span updateName(String name) {
        if (ended || name == null) return this;
        this.name = name;
        return this;
    }

    @Override
    public void end() {
        endInternal(clock.now());
    }

    @Override
    public void end(long timestamp, TimeUnit unit) {
        endInternal(unit.toNanos(timestamp));
    }

    private void endInternal(long endNanos) {
        synchronized (this) {
            if (ended) return;
            this.endEpochNanos = endNanos;
            this.ended = true;
        }
        for (SpanProcessor p : processors) {
            if (p.isEndRequired()) p.onEnd(this);
        }
    }

    @Override
    public SpanContext getSpanContext() {
        return context;
    }

    @Override
    public SpanContext getParentSpanContext() {
        return parentContext;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public synchronized boolean hasEnded() {
        return ended;
    }

    @Override
    public synchronized boolean isRecording() {
        return !ended;
    }

    @Override
    public synchronized SpanData toSpanData() {
        return new SpanData(
                context,
                parentContext,
                name,
                kind,
                startEpochNanos,
                endEpochNanos,
                attributes.build(),
                events != null ? List.copyOf(events) : List.of(),
                links,
                status,
                resource,
                scope);
    }
}
