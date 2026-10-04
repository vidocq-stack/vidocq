/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.humboldt.sdk.log.internal;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.common.Value;
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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Internal implementation of {@link LogRecordBuilder}.
 *
 * <p>Collect-then-emit: accumulates severity/body/attrs/event name/context until {@link #emit()},
 * then creates an immutable {@link LogRecordData} and notifies all processors.</p>
 */
public final class SdkLogRecordBuilder implements LogRecordBuilder {

    private static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message");
    private static final AttributeKey<String> EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace");

    private final Resource resource;
    private final InstrumentationScope scope;
    private final Clock clock;
    private final List<LogRecordProcessor> processors;

    private long timestampEpochNanos = 0L;
    private long observedEpochNanos = 0L;
    private Context context;
    private Severity severity = Severity.UNDEFINED_SEVERITY_NUMBER;
    private String severityText = "";
    /**
     * The body as set — a string body, even an empty one, is kept as {@code Value.of(string)}; {@code null} when
     * none was set. {@link LogRecordData} renders its string form.
     */
    private Value<?> body;
    private AttributesBuilder attributes = Attributes.builder();
    private String eventName = "";

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
        if (body != null) this.body = Value.of(body);
        return this;
    }

    /**
     * Keeps the structured body as is (the API default would flatten it with {@link Value#asString()}), so
     * that the OTLP exporter writes it as an {@code AnyValue}; text exporters print its string form.
     */
    @Override
    public LogRecordBuilder setBody(Value<?> body) {
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
    public LogRecordBuilder setEventName(String eventName) {
        this.eventName = eventName != null ? eventName : "";
        return this;
    }

    /**
     * Derives {@code exception.type}, {@code exception.message} and {@code exception.stacktrace} from
     * {@code throwable}, as the OpenTelemetry SDK 1.66 does: an attribute already set on this builder is kept,
     * a {@code null} message adds no attribute, and an attribute set after this call overrides the derived
     * value. {@code exception.type} is the canonical class name, or the binary name for a class that has
     * none (an anonymous or local class) — the same rule as {@code Span.recordException}; the OpenTelemetry
     * SDK drops the attribute in that case.
     */
    @Override
    public LogRecordBuilder setException(Throwable throwable) {
        if (throwable == null) return this;
        Attributes alreadySet = attributes.build();
        String canonicalName = throwable.getClass().getCanonicalName();
        putIfAbsent(alreadySet, EXCEPTION_TYPE,
                canonicalName != null ? canonicalName : throwable.getClass().getName());
        putIfAbsent(alreadySet, EXCEPTION_MESSAGE, throwable.getMessage());
        putIfAbsent(alreadySet, EXCEPTION_STACKTRACE, stackTraceOf(throwable));
        return this;
    }

    @Override
    public void emit() {
        long observed = observedEpochNanos > 0 ? observedEpochNanos : clock.now();
        long ts = timestampEpochNanos > 0 ? timestampEpochNanos : observed;
        Context ctx = context != null ? context : Context.current();
        SpanContext sc = Span.fromContext(ctx).getSpanContext();
        // The record derives the string form from the body value (once); no string body is passed here.
        LogRecordData record = new LogRecordData(
                resource, scope, ts, observed, sc,
                severity, severityText, null, attributes.build(), eventName, body);
        for (LogRecordProcessor p : processors) {
            p.onEmit(record);
        }
    }

    private void putIfAbsent(Attributes alreadySet, AttributeKey<String> key, String value) {
        if (value != null && alreadySet.get(key) == null) {
            attributes.put(key, value);
        }
    }

    private static String stackTraceOf(Throwable throwable) {
        StringWriter out = new StringWriter();
        try (PrintWriter writer = new PrintWriter(out)) {
            throwable.printStackTrace(writer);
        }
        return out.toString();
    }
}
