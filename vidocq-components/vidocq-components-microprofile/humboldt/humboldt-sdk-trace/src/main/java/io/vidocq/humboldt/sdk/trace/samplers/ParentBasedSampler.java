package io.vidocq.humboldt.sdk.trace.samplers;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.trace.data.LinkData;

import java.util.List;

/**
 * Sampler hiérarchique : si le parent existe et est sampled → recordAndSample,
 * sinon délègue au {@code rootSampler} pour les spans racines.
 *
 * <p>Variante simplifiée de la version OTel — ne configure pas séparément
 * {@code remoteParentNotSampled} etc., suit le bit {@code SAMPLED} du parent.</p>
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
