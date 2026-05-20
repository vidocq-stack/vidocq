package io.vidocq.humboldt.exporter.otlp.http.internal;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encode une collection de {@link LogRecordData} au format OTLP/HTTP-JSON.
 *
 * <p>Schéma : <a href="https://github.com/open-telemetry/opentelemetry-proto/blob/main/opentelemetry/proto/collector/logs/v1/logs_service.proto">logs_service.proto</a>.
 * Grouping par Resource puis par InstrumentationScope.</p>
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

    private static void writeResource(StringBuilder sb, Resource resource) {
        sb.append("\"resource\":{\"attributes\":");
        writeAttributesArray(sb, resource.attributes());
        sb.append("}");
    }

    private static void writeScopeLogs(StringBuilder sb, InstrumentationScope scope, List<LogRecordData> records) {
        sb.append("{\"scope\":{\"name\":");
        appendString(sb, scope.name());
        if (scope.version() != null) {
            sb.append(",\"version\":");
            appendString(sb, scope.version());
        }
        sb.append("},\"logRecords\":[");
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

    private static void writeAttributesArray(StringBuilder sb, Attributes attrs) {
        sb.append('[');
        List<Map.Entry<AttributeKey<?>, Object>> entries = new ArrayList<>(attrs.size());
        attrs.forEach((k, v) -> entries.add(new AbstractMap.SimpleEntry<>(k, v)));
        boolean first = true;
        for (var e : entries) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"key\":");
            appendString(sb, e.getKey().getKey());
            sb.append(",\"value\":");
            writeAnyValue(sb, e.getKey().getType(), e.getValue());
            sb.append('}');
        }
        sb.append(']');
    }

    private static void writeAnyValue(StringBuilder sb, AttributeType type, Object v) {
        sb.append('{');
        switch (type) {
            case STRING -> { sb.append("\"stringValue\":"); appendString(sb, (String) v); }
            case BOOLEAN -> sb.append("\"boolValue\":").append((boolean) v);
            case LONG -> sb.append("\"intValue\":\"").append((long) v).append('"');
            case DOUBLE -> sb.append("\"doubleValue\":").append((double) v);
            default -> { sb.append("\"stringValue\":"); appendString(sb, String.valueOf(v)); }
        }
        sb.append('}');
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        if (s == null) { sb.append('"'); return; }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
