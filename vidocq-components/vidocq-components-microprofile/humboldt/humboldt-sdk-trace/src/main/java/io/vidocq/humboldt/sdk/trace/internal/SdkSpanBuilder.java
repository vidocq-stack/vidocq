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
import io.vidocq.humboldt.sdk.trace.data.InstrumentationScope;
import io.vidocq.humboldt.sdk.trace.data.LinkData;
import io.vidocq.humboldt.sdk.trace.export.SpanProcessor;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import io.vidocq.humboldt.sdk.trace.samplers.SamplingResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Implémentation interne de {@link SpanBuilder} — collecte parent/links/attrs/kind/start
 * jusqu'à {@link #startSpan()} qui consulte le sampler et instancie un {@link SdkSpan}
 * ou retourne un span no-op si le sampler décide {@code DROP}.
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
            // Span non enregistré : on retourne un wrap d'un SpanContext valide
            // mais non-sampled (pour propagation), zéro-allocation côté SDK.
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

        // Merge des attrs builder + attrs additionnels du sampler (sampler gagne)
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
