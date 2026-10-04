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
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.trace.data.LinkData;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.data.StatusData;
import io.vidocq.humboldt.sdk.trace.export.SpanProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Internal implementation of {@link Span} + {@link ReadableSpan}.
 * Mutable until {@link #end()} ({@code synchronized} lock), immutable afterward.
 *
 * <p>Once {@code end()} has been called, any later mutation is silently ignored
 * (aligned with the reference OTel behavior).</p>
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
        // OTel rule: you cannot go down from OK. ERROR > OK > UNSET.
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

    /**
     * Records an {@code exception} event as the OpenTelemetry SDK 1.66 does: {@code exception.type} is the
     * canonical class name, {@code exception.message} is set only for a non-null message,
     * {@code exception.stacktrace} is the printed stack trace, and {@code additionalAttributes} are applied
     * last, so they override the derived values. For a class without a canonical name (an anonymous or local
     * class) {@code exception.type} falls back to the binary name, where the OpenTelemetry SDK drops the
     * attribute — the same rule as {@code LogRecordBuilder.setException} on the log side.
     */
    @Override
    public synchronized Span recordException(Throwable exception, Attributes additionalAttributes) {
        if (ended || exception == null) return this;
        AttributesBuilder builder = Attributes.builder();
        builder.put(AttributeKey.stringKey("exception.type"), exceptionType(exception));
        if (exception.getMessage() != null) {
            builder.put(AttributeKey.stringKey("exception.message"), exception.getMessage());
        }
        builder.put(AttributeKey.stringKey("exception.stacktrace"), stackTraceOf(exception));
        if (additionalAttributes != null) {
            builder.putAll(additionalAttributes);
        }
        return addEventInternal("exception", builder.build(), clock.now());
    }

    private static String exceptionType(Throwable t) {
        String canonical = t.getClass().getCanonicalName();
        return canonical != null ? canonical : t.getClass().getName();
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
