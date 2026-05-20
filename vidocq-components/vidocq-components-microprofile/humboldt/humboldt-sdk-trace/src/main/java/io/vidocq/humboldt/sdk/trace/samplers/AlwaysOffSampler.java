package io.vidocq.humboldt.sdk.trace.samplers;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.trace.data.LinkData;

import java.util.List;

final class AlwaysOffSampler implements Sampler {

    static final AlwaysOffSampler INSTANCE = new AlwaysOffSampler();

    private AlwaysOffSampler() {}

    @Override
    public SamplingResult shouldSample(
            Context parentContext,
            String traceId,
            String name,
            SpanKind kind,
            Attributes attributes,
            List<LinkData> parentLinks) {
        return SamplingResult.drop();
    }

    @Override
    public String description() {
        return "AlwaysOffSampler";
    }
}
