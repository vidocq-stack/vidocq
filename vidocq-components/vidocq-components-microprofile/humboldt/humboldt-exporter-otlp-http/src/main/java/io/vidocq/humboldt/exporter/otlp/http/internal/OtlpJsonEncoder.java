package io.vidocq.humboldt.exporter.otlp.http.internal;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.trace.data.EventData;
import io.vidocq.humboldt.sdk.trace.data.LinkData;
import io.vidocq.humboldt.sdk.trace.data.SpanData;

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
 * Encodes a collection of {@link SpanData} in OTLP/HTTP-JSON format.
 *
 * <p>Schema: <a href="https://github.com/open-telemetry/opentelemetry-proto/blob/main/opentelemetry/proto/collector/trace/v1/trace_service.proto">trace_service.proto</a>
 * and <a href="https://github.com/open-telemetry/opentelemetry-proto/blob/main/docs/specification.md#json-protobuf-encoding">JSON-proto encoding</a>.</p>
 *
 * <p>Grouped by Resource then by InstrumentationScope, conforming to the schema
 * {@code ExportTraceServiceRequest.resource_spans[].scope_spans[].spans[]}.
 * Common JSON plumbing (escaping, AnyValue, AttributesArray, Resource, Scope)
 * shared via {@link OtlpJsonCommon}.</p>
 */
public final class OtlpJsonEncoder {

    private OtlpJsonEncoder() {}

    public static String encode(Collection<SpanData> spans) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"resourceSpans\":[");

        Map<Resource, Map<InstrumentationScope, List<SpanData>>> grouped = groupByResourceAndScope(spans);
        boolean firstResource = true;
        for (var rEntry : grouped.entrySet()) {
            if (!firstResource) sb.append(',');
            firstResource = false;
            sb.append('{');
            writeResource(sb, rEntry.getKey());
            sb.append(",\"scopeSpans\":[");
            boolean firstScope = true;
            for (var sEntry : rEntry.getValue().entrySet()) {
                if (!firstScope) sb.append(',');
                firstScope = false;
                writeScopeSpans(sb, sEntry.getKey(), sEntry.getValue());
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

    private static Map<Resource, Map<InstrumentationScope, List<SpanData>>> groupByResourceAndScope(
            Collection<SpanData> spans) {
        Map<Resource, Map<InstrumentationScope, List<SpanData>>> out = new HashMap<>();
        for (SpanData s : spans) {
            out.computeIfAbsent(s.resource(), r -> new HashMap<>())
                    .computeIfAbsent(s.instrumentationScope(), sc -> new ArrayList<>())
                    .add(s);
        }
        return out;
    }

    private static void writeScopeSpans(StringBuilder sb, InstrumentationScope scope, List<SpanData> spans) {
        sb.append('{');
        writeScopeHeader(sb, scope);
        sb.append(",\"spans\":[");
        boolean first = true;
        for (SpanData s : spans) {
            if (!first) sb.append(',');
            first = false;
            writeSpan(sb, s);
        }
        sb.append(']');
        if (scope.schemaUrl() != null) {
            sb.append(",\"schemaUrl\":");
            appendString(sb, scope.schemaUrl());
        }
        sb.append('}');
    }

    private static void writeSpan(StringBuilder sb, SpanData s) {
        sb.append('{');
        sb.append("\"traceId\":\"").append(s.spanContext().getTraceId()).append('"');
        sb.append(",\"spanId\":\"").append(s.spanContext().getSpanId()).append('"');
        if (s.parentSpanContext() != null && s.parentSpanContext().isValid()) {
            sb.append(",\"parentSpanId\":\"").append(s.parentSpanContext().getSpanId()).append('"');
        }
        sb.append(",\"name\":");
        appendString(sb, s.name());
        sb.append(",\"kind\":").append(spanKindToInt(s.kind()));
        sb.append(",\"startTimeUnixNano\":\"").append(s.startEpochNanos()).append('"');
        sb.append(",\"endTimeUnixNano\":\"").append(s.endEpochNanos()).append('"');
        if (!s.attributes().isEmpty()) {
            sb.append(",\"attributes\":");
            writeAttributesArray(sb, s.attributes());
        }
        if (!s.events().isEmpty()) {
            sb.append(",\"events\":[");
            boolean first = true;
            for (EventData e : s.events()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"timeUnixNano\":\"").append(e.epochNanos()).append('"');
                sb.append(",\"name\":");
                appendString(sb, e.name());
                if (!e.attributes().isEmpty()) {
                    sb.append(",\"attributes\":");
                    writeAttributesArray(sb, e.attributes());
                }
                sb.append('}');
            }
            sb.append(']');
        }
        if (!s.links().isEmpty()) {
            sb.append(",\"links\":[");
            boolean first = true;
            for (LinkData l : s.links()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"traceId\":\"").append(l.spanContext().getTraceId()).append('"');
                sb.append(",\"spanId\":\"").append(l.spanContext().getSpanId()).append('"');
                if (!l.attributes().isEmpty()) {
                    sb.append(",\"attributes\":");
                    writeAttributesArray(sb, l.attributes());
                }
                sb.append('}');
            }
            sb.append(']');
        }
        sb.append(",\"status\":{");
        sb.append("\"code\":").append(statusCodeToInt(s.status().code()));
        if (!s.status().description().isEmpty()) {
            sb.append(",\"message\":");
            appendString(sb, s.status().description());
        }
        sb.append('}');
        sb.append('}');
    }

    private static int spanKindToInt(SpanKind kind) {
        // OTLP : SPAN_KIND_UNSPECIFIED=0, INTERNAL=1, SERVER=2, CLIENT=3, PRODUCER=4, CONSUMER=5
        return switch (kind) {
            case INTERNAL -> 1;
            case SERVER -> 2;
            case CLIENT -> 3;
            case PRODUCER -> 4;
            case CONSUMER -> 5;
        };
    }

    private static int statusCodeToInt(StatusCode code) {
        // OTLP : STATUS_CODE_UNSET=0, OK=1, ERROR=2
        return switch (code) {
            case UNSET -> 0;
            case OK -> 1;
            case ERROR -> 2;
        };
    }
}
