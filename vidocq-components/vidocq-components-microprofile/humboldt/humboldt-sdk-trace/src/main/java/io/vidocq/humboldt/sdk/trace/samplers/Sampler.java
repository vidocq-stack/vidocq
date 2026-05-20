package io.vidocq.humboldt.sdk.trace.samplers;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.trace.data.LinkData;

import java.util.List;

/**
 * Décide pour chaque nouveau span s'il est échantillonné (enregistré + exporté),
 * enregistré uniquement (pas exporté), ou abandonné.
 */
public interface Sampler {

    SamplingResult shouldSample(
            Context parentContext,
            String traceId,
            String name,
            SpanKind kind,
            Attributes attributes,
            List<LinkData> parentLinks);

    String description();

    static Sampler alwaysOn() {
        return AlwaysOnSampler.INSTANCE;
    }

    static Sampler alwaysOff() {
        return AlwaysOffSampler.INSTANCE;
    }

    static Sampler parentBased(Sampler rootSampler) {
        return new ParentBasedSampler(rootSampler);
    }

    static Sampler traceIdRatioBased(double ratio) {
        return new TraceIdRatioBasedSampler(ratio);
    }
}
