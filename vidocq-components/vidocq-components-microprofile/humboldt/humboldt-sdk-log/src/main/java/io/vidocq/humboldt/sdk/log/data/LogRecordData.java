package io.vidocq.humboldt.sdk.log.data;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;

/**
 * Immutable view of a LogRecord — consumed by
 * {@link io.vidocq.humboldt.sdk.log.export.LogRecordExporter}.
 *
 * @param resource              attributes of the telemetry source
 * @param scope                 identity of the source library
 * @param timestampEpochNanos   business timestamp of the event (0 if not provided)
 * @param observedEpochNanos    timestamp observed by the SDK when emitting
 * @param spanContext           current SpanContext at emission time ({@code valid() = false} if outside a trace)
 * @param severity              OTel severity ({@link Severity#UNDEFINED_SEVERITY_NUMBER} if not provided)
 * @param severityText          free-form text (for example {@code "INFO"}) — may be empty
 * @param body                  message body (often a String, may be empty)
 * @param attributes            additional attributes (never {@code null})
 */
public record LogRecordData(
        Resource resource,
        InstrumentationScope scope,
        long timestampEpochNanos,
        long observedEpochNanos,
        SpanContext spanContext,
        Severity severity,
        String severityText,
        String body,
        Attributes attributes) {

    public LogRecordData {
        if (resource == null) resource = Resource.empty();
        if (scope == null) scope = InstrumentationScope.of("");
        if (severity == null) severity = Severity.UNDEFINED_SEVERITY_NUMBER;
        if (severityText == null) severityText = "";
        if (body == null) body = "";
        if (attributes == null) attributes = Attributes.empty();
    }
}
