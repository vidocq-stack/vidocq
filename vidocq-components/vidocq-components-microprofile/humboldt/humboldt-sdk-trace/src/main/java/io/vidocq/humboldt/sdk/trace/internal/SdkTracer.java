package io.vidocq.humboldt.sdk.trace.internal;

import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.vidocq.humboldt.sdk.common.Clock;
import io.vidocq.humboldt.sdk.common.IdGenerator;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.trace.export.SpanProcessor;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;

import java.util.List;

/**
 * Tracer Humboldt — façade interne entre l'API publique
 * {@link io.opentelemetry.api.trace.Tracer} et le pipeline SDK
 * (sampler + processors + exporter).
 */
public final class SdkTracer implements Tracer {

    private final InstrumentationScope scope;
    private final Resource resource;
    private final Sampler sampler;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final List<SpanProcessor> processors;

    public SdkTracer(
            InstrumentationScope scope,
            Resource resource,
            Sampler sampler,
            IdGenerator idGenerator,
            Clock clock,
            List<SpanProcessor> processors) {
        this.scope = scope;
        this.resource = resource;
        this.sampler = sampler;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.processors = processors;
    }

    @Override
    public SpanBuilder spanBuilder(String spanName) {
        String safeName = (spanName == null || spanName.isEmpty()) ? "<unnamed>" : spanName;
        return new SdkSpanBuilder(
                safeName, scope, resource, sampler, idGenerator, clock, processors);
    }
}
