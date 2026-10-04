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
package io.vidocq.humboldt.exporter.otlp.http.internal;

import io.opentelemetry.api.trace.SpanContext;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.vidocq.humboldt.exporter.otlp.http.internal.OtlpJsonCommon.appendString;
import static io.vidocq.humboldt.exporter.otlp.http.internal.OtlpJsonCommon.writeAttributesArray;
import static io.vidocq.humboldt.exporter.otlp.http.internal.OtlpJsonCommon.writeResource;
import static io.vidocq.humboldt.exporter.otlp.http.internal.OtlpJsonCommon.writeScopeHeader;
import static io.vidocq.humboldt.exporter.otlp.http.internal.OtlpJsonCommon.writeValue;

/**
 * Encodes a collection of {@link LogRecordData} in OTLP/HTTP-JSON format.
 *
 * <p>Schema: <a href="https://github.com/open-telemetry/opentelemetry-proto/blob/main/opentelemetry/proto/collector/logs/v1/logs_service.proto">logs_service.proto</a>.
 * Grouped by Resource then by InstrumentationScope. Common JSON plumbing
 * shared via {@link OtlpJsonCommon} (escaping, array-aware AnyValue,
 * Attributes, Resource, Scope).</p>
 */
public final class OtlpJsonLogEncoder {

    private OtlpJsonLogEncoder() {}

    public static String encode(Collection<LogRecordData> records) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"resourceLogs\":[");

        Map<Resource, Map<InstrumentationScope, List<LogRecordData>>> grouped = groupByResourceAndScope(records);
        boolean firstResource = true;
        for (var rEntry : grouped.entrySet()) {
            if (!firstResource) sb.append(',');
            firstResource = false;
            sb.append('{');
            writeResource(sb, rEntry.getKey());
            sb.append(",\"scopeLogs\":[");
            boolean firstScope = true;
            for (var sEntry : rEntry.getValue().entrySet()) {
                if (!firstScope) sb.append(',');
                firstScope = false;
                writeScopeLogs(sb, sEntry.getKey(), sEntry.getValue());
            }
            sb.append(']');
            if (rEntry.getKey().schemaUrl() != null) {
                sb.append(",\"schemaUrl\":");
                appendString(sb, rEntry.getKey().schemaUrl());
            }
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static Map<Resource, Map<InstrumentationScope, List<LogRecordData>>> groupByResourceAndScope(
            Collection<LogRecordData> records) {
        Map<Resource, Map<InstrumentationScope, List<LogRecordData>>> out = new HashMap<>();
        for (LogRecordData r : records) {
            out.computeIfAbsent(r.resource(), x -> new HashMap<>())
                    .computeIfAbsent(r.scope(), x -> new ArrayList<>())
                    .add(r);
        }
        return out;
    }

    private static void writeScopeLogs(StringBuilder sb, InstrumentationScope scope, List<LogRecordData> records) {
        sb.append('{');
        writeScopeHeader(sb, scope);
        sb.append(",\"logRecords\":[");
        boolean first = true;
        for (LogRecordData r : records) {
            if (!first) sb.append(',');
            first = false;
            writeRecord(sb, r);
        }
        sb.append(']');
        if (scope.schemaUrl() != null) {
            sb.append(",\"schemaUrl\":");
            appendString(sb, scope.schemaUrl());
        }
        sb.append('}');
    }

    private static void writeRecord(StringBuilder sb, LogRecordData r) {
        sb.append('{');
        sb.append("\"timeUnixNano\":\"").append(r.timestampEpochNanos()).append('"');
        sb.append(",\"observedTimeUnixNano\":\"").append(r.observedEpochNanos()).append('"');
        int sevNum = r.severity().getSeverityNumber();
        if (sevNum > 0) {
            sb.append(",\"severityNumber\":").append(sevNum);
        }
        if (!r.severityText().isEmpty()) {
            sb.append(",\"severityText\":");
            appendString(sb, r.severityText());
        }
        if (r.bodyValue() != null) {
            // a string body is a stringValue; a structured body keeps its AnyValue shape
            sb.append(",\"body\":");
            writeValue(sb, r.bodyValue());
        }
        if (!r.attributes().isEmpty()) {
            sb.append(",\"attributes\":");
            writeAttributesArray(sb, r.attributes());
        }
        SpanContext sc = r.spanContext();
        if (sc != null && sc.isValid()) {
            sb.append(",\"traceId\":\"").append(sc.getTraceId()).append('"');
            sb.append(",\"spanId\":\"").append(sc.getSpanId()).append('"');
            sb.append(",\"flags\":").append(sc.getTraceFlags().asByte() & 0xFF);
        }
        if (!r.eventName().isEmpty()) {
            // OTLP LogRecord.event_name (field 12), lowerCamelCase in the JSON mapping
            sb.append(",\"eventName\":");
            appendString(sb, r.eventName());
        }
        sb.append('}');
    }
}
