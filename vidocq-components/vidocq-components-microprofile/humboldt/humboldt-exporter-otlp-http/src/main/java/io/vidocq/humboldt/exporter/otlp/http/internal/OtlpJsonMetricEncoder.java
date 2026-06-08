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

import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.HistogramPointData;
import io.vidocq.humboldt.sdk.metric.data.LongPointData;
import io.vidocq.humboldt.sdk.metric.data.MetricData;
import io.vidocq.humboldt.sdk.metric.data.PointData;

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
 * Encodes a collection of {@link MetricData} in OTLP/HTTP-JSON format.
 *
 * <p>Schema: <a href="https://github.com/open-telemetry/opentelemetry-proto/blob/main/opentelemetry/proto/collector/metrics/v1/metrics_service.proto">metrics_service.proto</a>.
 * Grouped by Resource then by InstrumentationScope. Common JSON plumbing
 * shared via {@link OtlpJsonCommon} (escaping, array-aware AnyValue,
 * Attributes, Resource, Scope).</p>
 *
 * <p>M4 MVP: only Counter (sum/asInt) and Histogram (explicit buckets) are
 * supported. Gauge / ExponentialHistogram = M4b.</p>
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

    private static void writeScopeMetrics(StringBuilder sb, InstrumentationScope scope, List<MetricData> metrics) {
        sb.append('{');
        writeScopeHeader(sb, scope);
        sb.append(",\"metrics\":[");
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
}
