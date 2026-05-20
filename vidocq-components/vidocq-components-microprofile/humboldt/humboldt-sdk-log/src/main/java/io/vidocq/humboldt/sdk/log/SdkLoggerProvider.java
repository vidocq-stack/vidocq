package io.vidocq.humboldt.sdk.log;

import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.LoggerBuilder;
import io.opentelemetry.api.logs.LoggerProvider;
import io.vidocq.humboldt.sdk.common.Clock;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.log.export.LogRecordProcessor;
import io.vidocq.humboldt.sdk.log.internal.SdkLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implémentation Humboldt de {@link LoggerProvider} — point d'entrée du SDK Log.
 *
 * <p>Construit via {@link #builder()}. Immutable côté configuration. Cache de
 * {@code Logger} par scope name.</p>
 */
public final class SdkLoggerProvider implements LoggerProvider, AutoCloseable {

    private final Resource resource;
    private final Clock clock;
    private final List<LogRecordProcessor> processors;
    private final Map<String, SdkLogger> loggers = new ConcurrentHashMap<>();

    private SdkLoggerProvider(Builder b) {
        this.resource = b.resource;
        this.clock = b.clock;
        this.processors = Collections.unmodifiableList(new ArrayList<>(b.processors));
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Logger get(String instrumentationScopeName) {
        String name = instrumentationScopeName == null ? "" : instrumentationScopeName;
        return loggers.computeIfAbsent(name,
                n -> new SdkLogger(InstrumentationScope.of(n), resource, clock, processors));
    }

    @Override
    public LoggerBuilder loggerBuilder(String instrumentationScopeName) {
        // M5 MVP : version/schemaUrl/attributes ignorés (un seul SdkLogger par nom).
        return new LoggerBuilder() {
            @Override public LoggerBuilder setSchemaUrl(String url) { return this; }
            @Override public LoggerBuilder setInstrumentationVersion(String v) { return this; }
            @Override public Logger build() { return get(instrumentationScopeName); }
        };
    }

    public Resource getResource() {
        return resource;
    }

    public CompletableResultCode flush() {
        List<CompletableResultCode> codes = new ArrayList<>(processors.size());
        for (LogRecordProcessor p : processors) codes.add(p.flush());
        return CompletableResultCode.ofAll(codes);
    }

    public CompletableResultCode shutdown() {
        List<CompletableResultCode> codes = new ArrayList<>(processors.size());
        for (LogRecordProcessor p : processors) codes.add(p.shutdown());
        return CompletableResultCode.ofAll(codes);
    }

    @Override
    public void close() {
        shutdown().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    public static final class Builder {
        private Resource resource = Resource.empty();
        private Clock clock = Clock.system();
        private final List<LogRecordProcessor> processors = new ArrayList<>();

        public Builder setResource(Resource resource) {
            this.resource = resource != null ? resource : Resource.empty();
            return this;
        }

        public Builder setClock(Clock clock) {
            if (clock != null) this.clock = clock;
            return this;
        }

        public Builder addLogRecordProcessor(LogRecordProcessor processor) {
            if (processor != null) processors.add(processor);
            return this;
        }

        public SdkLoggerProvider build() {
            return new SdkLoggerProvider(this);
        }
    }
}
