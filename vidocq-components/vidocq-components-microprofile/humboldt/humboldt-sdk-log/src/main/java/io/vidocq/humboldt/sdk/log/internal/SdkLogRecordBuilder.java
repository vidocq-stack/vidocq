package io.vidocq.humboldt.sdk.log.internal;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.common.Clock;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;
import io.vidocq.humboldt.sdk.log.export.LogRecordProcessor;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Internal implementation of {@link LogRecordBuilder}.
 *
 * <p>Collect-then-emit: accumulates severity/body/attrs/context until {@link #emit()},
 * then creates an immutable {@link LogRecordData} and notifies all processors.</p>
 */
public final class SdkLogRecordBuilder implements LogRecordBuilder {

    private final Resource resource;
    private final InstrumentationScope scope;
    private final Clock clock;
    private final List<LogRecordProcessor> processors;

    private long timestampEpochNanos = 0L;
    private long observedEpochNanos = 0L;
    private Context context;
    private Severity severity = Severity.UNDEFINED_SEVERITY_NUMBER;
    private String severityText = "";
    private String body = "";
    private AttributesBuilder attributes = Attributes.builder();

    public SdkLogRecordBuilder(
            Resource resource, InstrumentationScope scope,
            Clock clock, List<LogRecordProcessor> processors) {
        this.resource = resource;
        this.scope = scope;
        this.clock = clock;
        this.processors = processors;
    }

    @Override
    public LogRecordBuilder setTimestamp(long timestamp, TimeUnit unit) {
        if (unit != null) this.timestampEpochNanos = unit.toNanos(timestamp);
        return this;
    }

    @Override
    public LogRecordBuilder setTimestamp(Instant instant) {
        if (instant != null) {
            this.timestampEpochNanos = Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L)
                    + instant.getNano();
        }
        return this;
    }

    @Override
    public LogRecordBuilder setObservedTimestamp(long timestamp, TimeUnit unit) {
        if (unit != null) this.observedEpochNanos = unit.toNanos(timestamp);
        return this;
    }

    @Override
    public LogRecordBuilder setObservedTimestamp(Instant instant) {
        if (instant != null) {
            this.observedEpochNanos = Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L)
                    + instant.getNano();
        }
        return this;
    }

    @Override
    public LogRecordBuilder setContext(Context context) {
        this.context = context;
        return this;
    }

    @Override
    public LogRecordBuilder setSeverity(Severity severity) {
        if (severity != null) this.severity = severity;
        return this;
    }

    @Override
    public LogRecordBuilder setSeverityText(String severityText) {
        if (severityText != null) this.severityText = severityText;
        return this;
    }

    @Override
    public LogRecordBuilder setBody(String body) {
        if (body != null) this.body = body;
        return this;
    }

    @Override
    public <T> LogRecordBuilder setAttribute(AttributeKey<T> key, T value) {
        if (key != null && !key.getKey().isEmpty() && value != null) {
            attributes.put(key, value);
        }
        return this;
    }

    @Override
    public void emit() {
        long observed = observedEpochNanos > 0 ? observedEpochNanos : clock.now();
        long ts = timestampEpochNanos > 0 ? timestampEpochNanos : observed;
        Context ctx = context != null ? context : Context.current();
        SpanContext sc = Span.fromContext(ctx).getSpanContext();
        LogRecordData record = new LogRecordData(
                resource, scope, ts, observed, sc,
                severity, severityText, body, attributes.build());
        for (LogRecordProcessor p : processors) {
            p.onEmit(record);
        }
    }
}
