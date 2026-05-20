package io.vidocq.humboldt.exporter.otlp.http.internal;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.HistogramPointData;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;
import io.vidocq.humboldt.sdk.metric.data.LongPointData;
import io.vidocq.humboldt.sdk.metric.data.MetricData;
import io.vidocq.humboldt.sdk.metric.data.PointData;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encode une collection de {@link MetricData} au format OTLP/HTTP-JSON.
 *
 * <p>Schéma : <a href="https://github.com/open-telemetry/opentelemetry-proto/blob/main/opentelemetry/proto/collector/metrics/v1/metrics_service.proto">metrics_service.proto</a>.
 * Grouping par Resource puis par InstrumentationScope.</p>
 *
 * <p>M4 MVP : seuls Counter (sum/asInt) et Histogram (explicit buckets) sont supportés.
 * Gauge / ExponentialHistogram = M4b.</p>
 */
public final class OtlpJsonMetricEncoder {

    private OtlpJsonMetricEncoder() {}

    public static String encode(Collection<MetricData> metrics) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"resourceMetrics\":[");

        Map<Resource, Map<InstrumentationScope, List<MetricData>>> grouped = groupByResourceAndScope(metrics);
        boolean firstResource = true;
        for (var rEntry : grouped.entrySet()) {
            if (!firstResource) sb.append(',');
            firstResource = false;
            sb.append('{');
            writeResource(sb, rEntry.getKey());
            sb.append(",\"scopeMetrics\":[");
            boolean firstScope = true;
            for (var sEntry : rEntry.getValue().entrySet()) {
                if (!firstScope) sb.append(',');
                firstScope = false;
                writeScopeMetrics(sb, sEntry.getKey(), sEntry.getValue());
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

    private static Map<Resource, Map<InstrumentationScope, List<MetricData>>> groupByResourceAndScope(
            Collection<MetricData> metrics) {
        Map<Resource, Map<InstrumentationScope, List<MetricData>>> out = new HashMap<>();
        for (MetricData m : metrics) {
            out.computeIfAbsent(m.resource(), r -> new HashMap<>())
                    .computeIfAbsent(m.scope(), sc -> new ArrayList<>())
                    .add(m);
        }
        return out;
    }

    private static void writeResource(StringBuilder sb, Resource resource) {
        sb.append("\"resource\":{\"attributes\":");
        writeAttributesArray(sb, resource.attributes());
        sb.append("}");
    }

    private static void writeScopeMetrics(StringBuilder sb, InstrumentationScope scope, List<MetricData> metrics) {
        sb.append("{\"scope\":{\"name\":");
        appendString(sb, scope.name());
        if (scope.version() != null) {
            sb.append(",\"version\":");
            appendString(sb, scope.version());
        }
        sb.append("},\"metrics\":[");
        boolean first = true;
        for (MetricData m : metrics) {
            if (!first) sb.append(',');
            first = false;
            writeMetric(sb, m);
        }
        sb.append(']');
        if (scope.schemaUrl() != null) {
            sb.append(",\"schemaUrl\":");
            appendString(sb, scope.schemaUrl());
        }
        sb.append('}');
    }

    private static void writeMetric(StringBuilder sb, MetricData m) {
        sb.append("{\"name\":");
        appendString(sb, m.name());
        if (!m.description().isEmpty()) {
            sb.append(",\"description\":");
            appendString(sb, m.description());
        }
        if (!m.unit().isEmpty()) {
            sb.append(",\"unit\":");
            appendString(sb, m.unit());
        }

        switch (m.instrumentType()) {
            case COUNTER, UP_DOWN_COUNTER, OBSERVABLE_COUNTER, OBSERVABLE_UP_DOWN_COUNTER ->
                    writeSum(sb, m);
            case HISTOGRAM -> writeHistogram(sb, m);
            case OBSERVABLE_GAUGE -> writeGauge(sb, m);
        }
        sb.append('}');
    }

    private static void writeSum(StringBuilder sb, MetricData m) {
        sb.append(",\"sum\":{\"dataPoints\":[");
        boolean first = true;
        for (PointData p : m.points()) {
            if (!(p instanceof LongPointData lp)) continue;
            if (!first) sb.append(',');
            first = false;
            writeLongDataPoint(sb, lp);
        }
        sb.append("],\"aggregationTemporality\":")
                .append(temporalityToInt(m.temporality()))
                .append(",\"isMonotonic\":").append(m.monotonic()).append('}');
    }

    private static void writeHistogram(StringBuilder sb, MetricData m) {
        sb.append(",\"histogram\":{\"dataPoints\":[");
        boolean first = true;
        for (PointData p : m.points()) {
            if (!(p instanceof HistogramPointData hp)) continue;
            if (!first) sb.append(',');
            first = false;
            writeHistogramDataPoint(sb, hp);
        }
        sb.append("],\"aggregationTemporality\":")
                .append(temporalityToInt(m.temporality())).append('}');
    }

    private static void writeGauge(StringBuilder sb, MetricData m) {
        sb.append(",\"gauge\":{\"dataPoints\":[");
        boolean first = true;
        for (PointData p : m.points()) {
            if (!(p instanceof LongPointData lp)) continue;
            if (!first) sb.append(',');
            first = false;
            writeLongDataPoint(sb, lp);
        }
        sb.append("]}");
    }

    private static void writeLongDataPoint(StringBuilder sb, LongPointData p) {
        sb.append("{\"startTimeUnixNano\":\"").append(p.startEpochNanos()).append('"');
        sb.append(",\"timeUnixNano\":\"").append(p.epochNanos()).append('"');
        sb.append(",\"asInt\":\"").append(p.value()).append('"');
        if (!p.attributes().isEmpty()) {
            sb.append(",\"attributes\":");
            writeAttributesArray(sb, p.attributes());
        }
        sb.append('}');
    }

    private static void writeHistogramDataPoint(StringBuilder sb, HistogramPointData p) {
        sb.append("{\"startTimeUnixNano\":\"").append(p.startEpochNanos()).append('"');
        sb.append(",\"timeUnixNano\":\"").append(p.epochNanos()).append('"');
        sb.append(",\"count\":\"").append(p.count()).append('"');
        sb.append(",\"sum\":").append(p.sum());
        if (!Double.isNaN(p.min())) sb.append(",\"min\":").append(p.min());
        if (!Double.isNaN(p.max())) sb.append(",\"max\":").append(p.max());
        sb.append(",\"explicitBounds\":[");
        for (int i = 0; i < p.boundaries().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(p.boundaries().get(i));
        }
        sb.append("],\"bucketCounts\":[");
        for (int i = 0; i < p.bucketCounts().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(p.bucketCounts().get(i)).append('"');
        }
        sb.append(']');
        if (!p.attributes().isEmpty()) {
            sb.append(",\"attributes\":");
            writeAttributesArray(sb, p.attributes());
        }
        sb.append('}');
    }

    private static int temporalityToInt(AggregationTemporality t) {
        // OTLP : UNSPECIFIED=0, DELTA=1, CUMULATIVE=2
        return switch (t) {
            case DELTA -> 1;
            case CUMULATIVE -> 2;
        };
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
            case STRING -> {
                sb.append("\"stringValue\":");
                appendString(sb, (String) v);
            }
            case BOOLEAN -> sb.append("\"boolValue\":").append((boolean) v);
            case LONG -> sb.append("\"intValue\":\"").append((long) v).append('"');
            case DOUBLE -> sb.append("\"doubleValue\":").append((double) v);
            default -> sb.append("\"stringValue\":\"").append(String.valueOf(v)).append('"');
        }
        sb.append('}');
    }

    /**
     * Délègue à {@link JsonEscape} (package-private) en passant par l'autre encoder —
     * évite la duplication de l'escape JSON.
     */
    private static void appendString(StringBuilder sb, String s) {
        // Sécurité : escape JSON minimal inline (échappement des chars de contrôle, ", \)
        sb.append('"');
        if (s == null) {
            sb.append('"');
            return;
        }
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
