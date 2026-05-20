package io.vidocq.humboldt.sdk.trace;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.vidocq.humboldt.sdk.common.Clock;
import io.vidocq.humboldt.sdk.common.IdGenerator;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.trace.data.InstrumentationScope;
import io.vidocq.humboldt.sdk.trace.export.CompletableResultCode;
import io.vidocq.humboldt.sdk.trace.export.SpanProcessor;
import io.vidocq.humboldt.sdk.trace.internal.SdkTracer;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implémentation Humboldt de {@link TracerProvider} — point d'entrée du SDK Trace.
 *
 * <p>Construit via {@link #builder()}. Immutable côté configuration ; la pool de
 * {@code Tracer} par scope est cachée pour éviter d'instancier 1 {@code SdkTracer}
 * par appel à {@code get(...)}.</p>
 */
public final class SdkTracerProvider implements TracerProvider, AutoCloseable {

    private final Resource resource;
    private final Sampler sampler;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final List<SpanProcessor> processors;
    private final Map<String, SdkTracer> tracers = new ConcurrentHashMap<>();

    private SdkTracerProvider(Builder b) {
        this.resource = b.resource;
        this.sampler = b.sampler;
        this.idGenerator = b.idGenerator;
        this.clock = b.clock;
        this.processors = Collections.unmodifiableList(new ArrayList<>(b.processors));
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Tracer get(String instrumentationScopeName) {
        String name = instrumentationScopeName == null ? "" : instrumentationScopeName;
        return tracers.computeIfAbsent(name, n -> new SdkTracer(
                InstrumentationScope.of(n),
                resource, sampler, idGenerator, clock, processors));
    }

    @Override
    public Tracer get(String instrumentationScopeName, String instrumentationScopeVersion) {
        // M2 : cache par scope name uniquement ; la version est portée dans l'InstrumentationScope
        // une fois en M6 lorsque tracerBuilder() sera ajouté.
        return get(instrumentationScopeName);
    }

    public Resource getResource() {
        return resource;
    }

    public Sampler getSampler() {
        return sampler;
    }

    public List<SpanProcessor> getSpanProcessors() {
        return processors;
    }

    public CompletableResultCode flush() {
        List<CompletableResultCode> codes = new ArrayList<>(processors.size());
        for (SpanProcessor p : processors) codes.add(p.flush());
        return CompletableResultCode.ofAll(codes);
    }

    public CompletableResultCode shutdown() {
        List<CompletableResultCode> codes = new ArrayList<>(processors.size());
        for (SpanProcessor p : processors) codes.add(p.shutdown());
        return CompletableResultCode.ofAll(codes);
    }

    @Override
    public void close() {
        shutdown().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    public static final class Builder {
        private Resource resource = Resource.empty();
        private Sampler sampler = Sampler.parentBased(Sampler.alwaysOn());
        private IdGenerator idGenerator = IdGenerator.random128();
        private Clock clock = Clock.system();
        private final List<SpanProcessor> processors = new ArrayList<>();

        public Builder setResource(Resource resource) {
            this.resource = resource != null ? resource : Resource.empty();
            return this;
        }

        public Builder setSampler(Sampler sampler) {
            if (sampler != null) this.sampler = sampler;
            return this;
        }

        public Builder setIdGenerator(IdGenerator idGenerator) {
            if (idGenerator != null) this.idGenerator = idGenerator;
            return this;
        }

        public Builder setClock(Clock clock) {
            if (clock != null) this.clock = clock;
            return this;
        }

        public Builder addSpanProcessor(SpanProcessor processor) {
            if (processor != null) processors.add(processor);
            return this;
        }

        public SdkTracerProvider build() {
            return new SdkTracerProvider(this);
        }
    }
}
