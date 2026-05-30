package io.vidocq.humboldt.sdk.trace.samplers;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.trace.data.LinkData;

import java.util.List;

/**
 * Hierarchical sampler: if the parent exists and is sampled → recordAndSample,
 * otherwise delegates to {@code rootSampler} for root spans.
 *
 * <p>Simplified variant of the OTel version — it does not configure
 * {@code remoteParentNotSampled}, etc. separately, and follows the parent's {@code SAMPLED} bit.</p>
 */
public final class ParentBasedSampler implements Sampler {

    private final Sampler rootSampler;

    ParentBasedSampler(Sampler rootSampler) {
        if (rootSampler == null) throw new NullPointerException("rootSampler");
        this.rootSampler = rootSampler;
    }

    @Override
    public SamplingResult shouldSample(
            Context parentContext,
            String traceId,
            String name,
            SpanKind kind,
            Attributes attributes,
            List<LinkData> parentLinks) {
        SpanContext parent = Span.fromContext(parentContext).getSpanContext();
        if (parent.isValid()) {
            return parent.isSampled() ? SamplingResult.recordAndSample() : SamplingResult.drop();
        }
        return rootSampler.shouldSample(parentContext, traceId, name, kind, attributes, parentLinks);
    }

    @Override
    public String description() {
        return "ParentBased(root=" + rootSampler.description() + ")";
    }
}
