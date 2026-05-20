package io.vidocq.humboldt.sdk.log.internal;

import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.vidocq.humboldt.sdk.common.Clock;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.log.export.LogRecordProcessor;

import java.util.List;

/**
 * Logger Humboldt — façade {@link Logger} qui produit des {@link SdkLogRecordBuilder}.
 */
public final class SdkLogger implements Logger {

    private final InstrumentationScope scope;
    private final Resource resource;
    private final Clock clock;
    private final List<LogRecordProcessor> processors;

    public SdkLogger(
            InstrumentationScope scope, Resource resource, Clock clock,
            List<LogRecordProcessor> processors) {
        this.scope = scope;
        this.resource = resource;
        this.clock = clock;
        this.processors = processors;
    }

    @Override
    public LogRecordBuilder logRecordBuilder() {
        return new SdkLogRecordBuilder(resource, scope, clock, processors);
    }
}
