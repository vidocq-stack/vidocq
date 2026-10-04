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
package io.vidocq.humboldt.sdk.log.data;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;

/**
 * Immutable view of a LogRecord — consumed by
 * {@link io.vidocq.humboldt.sdk.log.export.LogRecordExporter}.
 *
 * <p>The body is carried twice: {@link #bodyValue()} is the body as set through the log API — a plain string
 * or a structured {@link Value} (map, array, bytes...) set with {@code LogRecordBuilder.setBody(Value)} — and
 * {@link #body()} is its string form ({@link Value#asString()}: the string itself for a string body, a JSON
 * rendering for a map or an array). Exporters that understand {@code AnyValue} (OTLP) write
 * {@link #bodyValue()}; text exporters print {@link #body()}.</p>
 *
 * @param resource              attributes of the telemetry source
 * @param scope                 identity of the source library
 * @param timestampEpochNanos   business timestamp of the event (0 if not provided)
 * @param observedEpochNanos    timestamp observed by the SDK when emitting
 * @param spanContext           current SpanContext at emission time ({@code valid() = false} if outside a trace)
 * @param severity              OTel severity ({@link Severity#UNDEFINED_SEVERITY_NUMBER} if not provided)
 * @param severityText          free-form text (for example {@code "INFO"}) — may be empty
 * @param body                  string form of the body, empty when there is none; replaced by
 *                              {@code bodyValue.asString()} when {@code bodyValue} is not {@code null}
 * @param attributes            additional attributes (never {@code null})
 * @param eventName             event name set through {@code LogRecordBuilder.setEventName} — empty for a
 *                              plain log record (a record with a non-empty event name is an Event)
 * @param bodyValue             the body as set, or {@code null} when there is none; a non-empty {@code body}
 *                              given without a {@code bodyValue} becomes {@code Value.of(body)}
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
        Attributes attributes,
        String eventName,
        Value<?> bodyValue) {

    public LogRecordData {
        if (resource == null) resource = Resource.empty();
        if (scope == null) scope = InstrumentationScope.of("");
        if (severity == null) severity = Severity.UNDEFINED_SEVERITY_NUMBER;
        if (severityText == null) severityText = "";
        if (bodyValue != null) {
            body = bodyValue.asString();
        } else if (body != null && !body.isEmpty()) {
            bodyValue = Value.of(body);
        }
        if (body == null) body = "";
        if (attributes == null) attributes = Attributes.empty();
        if (eventName == null) eventName = "";
    }

    /**
     * Log record with a string body. This is the canonical constructor the record exposed before it carried
     * structured bodies, kept so that code building records directly (custom exporters, tests) still compiles
     * and links.
     */
    public LogRecordData(
            Resource resource,
            InstrumentationScope scope,
            long timestampEpochNanos,
            long observedEpochNanos,
            SpanContext spanContext,
            Severity severity,
            String severityText,
            String body,
            Attributes attributes,
            String eventName) {
        this(resource, scope, timestampEpochNanos, observedEpochNanos, spanContext,
                severity, severityText, body, attributes, eventName, null);
    }

    /**
     * Log record with a string body and without an event name. This is the constructor the record exposed
     * before it carried event names, kept so that code building records directly (custom exporters, tests)
     * still compiles and links.
     */
    public LogRecordData(
            Resource resource,
            InstrumentationScope scope,
            long timestampEpochNanos,
            long observedEpochNanos,
            SpanContext spanContext,
            Severity severity,
            String severityText,
            String body,
            Attributes attributes) {
        this(resource, scope, timestampEpochNanos, observedEpochNanos, spanContext,
                severity, severityText, body, attributes, "", null);
    }
}
