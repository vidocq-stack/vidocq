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
 * @param eventName             event name set through {@code LogRecordBuilder.setEventName} — empty for a
 *                              plain log record (a record with a non-empty event name is an Event)
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
        String eventName) {

    public LogRecordData {
        if (resource == null) resource = Resource.empty();
        if (scope == null) scope = InstrumentationScope.of("");
        if (severity == null) severity = Severity.UNDEFINED_SEVERITY_NUMBER;
        if (severityText == null) severityText = "";
        if (body == null) body = "";
        if (attributes == null) attributes = Attributes.empty();
        if (eventName == null) eventName = "";
    }

    /**
     * Log record without an event name. This is the constructor the record exposed before it carried event
     * names, kept so that code building records directly (custom exporters, tests) still compiles.
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
                severity, severityText, body, attributes, "");
    }
}
