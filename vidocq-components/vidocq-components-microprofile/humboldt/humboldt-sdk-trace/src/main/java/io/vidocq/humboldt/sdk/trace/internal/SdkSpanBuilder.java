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
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.common.Clock;
import io.vidocq.humboldt.sdk.common.IdGenerator;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.trace.data.LinkData;
import io.vidocq.humboldt.sdk.trace.export.SpanProcessor;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import io.vidocq.humboldt.sdk.trace.samplers.SamplingResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Internal implementation of {@link SpanBuilder} — collects parent/links/attrs/kind/start
 * until {@link #startSpan()}, which consults the sampler and instantiates an {@link SdkSpan}
 * or returns a no-op span if the sampler decides {@code DROP}.
 */
public final class SdkSpanBuilder implements SpanBuilder {

    private final String name;
    private final InstrumentationScope scope;
    private final Resource resource;
    private final Sampler sampler;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final List<SpanProcessor> processors;

    private Context parent;
    private boolean noParent = false;
    private SpanKind kind = SpanKind.INTERNAL;
    private long startEpochNanos = 0L;
    private final AttributesBuilder attributes = Attributes.builder();
    private final List<LinkData> links = new ArrayList<>();

    public SdkSpanBuilder(
            String name,
            InstrumentationScope scope,
            Resource resource,
            Sampler sampler,
            IdGenerator idGenerator,
            Clock clock,
            List<SpanProcessor> processors) {
        this.name = name;
        this.scope = scope;
        this.resource = resource;
        this.sampler = sampler;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.processors = processors;
    }

    @Override
    public SpanBuilder setParent(Context context) {
        this.parent = context;
        this.noParent = false;
        return this;
    }

    @Override
    public SpanBuilder setNoParent() {
        this.parent = null;
        this.noParent = true;
        return this;
    }

    @Override
    public SpanBuilder addLink(SpanContext spanContext) {
        return addLink(spanContext, Attributes.empty());
    }

    @Override
    public SpanBuilder addLink(SpanContext spanContext, Attributes attributes) {
        if (spanContext != null && spanContext.isValid()) {
            links.add(new LinkData(spanContext, attributes != null ? attributes : Attributes.empty()));
        }
        return this;
    }

    @Override
    public <T> SpanBuilder setAttribute(AttributeKey<T> key, T value) {
        if (key != null && !key.getKey().isEmpty() && value != null) {
            attributes.put(key, value);
        }
        return this;
    }

    @Override
    public SpanBuilder setAttribute(String key, String value) {
        return setAttribute(AttributeKey.stringKey(key), value);
    }

    @Override
    public SpanBuilder setAttribute(String key, long value) {
        return setAttribute(AttributeKey.longKey(key), value);
    }

    @Override
    public SpanBuilder setAttribute(String key, double value) {
        return setAttribute(AttributeKey.doubleKey(key), value);
    }

    @Override
    public SpanBuilder setAttribute(String key, boolean value) {
        return setAttribute(AttributeKey.booleanKey(key), value);
    }

    @Override
    public SpanBuilder setSpanKind(SpanKind spanKind) {
        if (spanKind != null) this.kind = spanKind;
        return this;
    }

    @Override
    public SpanBuilder setStartTimestamp(long startTimestamp, TimeUnit unit) {
        if (unit != null && startTimestamp >= 0) {
            this.startEpochNanos = unit.toNanos(startTimestamp);
        }
        return this;
    }

    @Override
    public Span startSpan() {
        Context parentCtx = noParent ? Context.root()
                : (parent != null ? parent : Context.current());
        SpanContext parentSpanContext = Span.fromContext(parentCtx).getSpanContext();

        String traceId = parentSpanContext.isValid()
                ? parentSpanContext.getTraceId()
                : idGenerator.generateTraceId();
        String spanId = idGenerator.generateSpanId();

        SamplingResult sampling = sampler.shouldSample(
                parentCtx, traceId, name, kind, attributes.build(), links);
        SamplingResult.Decision decision = sampling.decision();

        if (decision == SamplingResult.Decision.DROP) {
            // Span not recorded: return a wrapper around a valid SpanContext
            // but non-sampled (for propagation), with zero allocation on the SDK side.
            SpanContext ctx = SpanContext.create(
                    traceId, spanId,
                    TraceFlags.getDefault(),
                    parentSpanContext.getTraceState());
            return Span.wrap(ctx);
        }

        TraceFlags flags = decision == SamplingResult.Decision.RECORD_AND_SAMPLE
                ? TraceFlags.getSampled()
                : TraceFlags.getDefault();
        SpanContext newCtx = SpanContext.create(
                traceId, spanId, flags, parentSpanContext.getTraceState());

        // Merge builder attrs + additional attrs from the sampler (the sampler wins)
        AttributesBuilder merged = attributes.build().toBuilder();
        merged.putAll(sampling.attributes());

        long start = startEpochNanos > 0 ? startEpochNanos : clock.now();
        SdkSpan span = new SdkSpan(
                newCtx,
                parentSpanContext.isValid() ? parentSpanContext : null,
                name, kind, start,
                merged.build(),
                List.copyOf(links),
                resource, scope, clock,
                processors);

        for (SpanProcessor p : processors) {
            if (p.isStartRequired()) p.onStart(parentCtx, span);
        }
        return span;
    }

}
