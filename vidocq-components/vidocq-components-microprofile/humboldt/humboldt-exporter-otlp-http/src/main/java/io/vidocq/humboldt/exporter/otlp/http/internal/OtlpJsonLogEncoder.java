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

/**
 * Encode une collection de {@link LogRecordData} au format OTLP/HTTP-JSON.
 *
 * <p>Schéma : <a href="https://github.com/open-telemetry/opentelemetry-proto/blob/main/opentelemetry/proto/collector/logs/v1/logs_service.proto">logs_service.proto</a>.
 * Grouping par Resource puis par InstrumentationScope. Plumbing JSON commun
 * mutualisé via {@link OtlpJsonCommon} (escape, AnyValue array-aware,
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
        if (!r.body().isEmpty()) {
            sb.append(",\"body\":{\"stringValue\":");
            appendString(sb, r.body());
            sb.append('}');
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
        sb.append('}');
    }
}
