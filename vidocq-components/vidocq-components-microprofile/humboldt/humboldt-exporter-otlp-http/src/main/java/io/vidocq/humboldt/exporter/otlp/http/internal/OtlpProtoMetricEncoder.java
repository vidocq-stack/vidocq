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

import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.DoublePointData;
import io.vidocq.humboldt.sdk.metric.data.HistogramPointData;
import io.vidocq.humboldt.sdk.metric.data.LongPointData;
import io.vidocq.humboldt.sdk.metric.data.MetricData;
import io.vidocq.humboldt.sdk.metric.data.PointData;

import java.util.Collection;

import static io.vidocq.humboldt.exporter.otlp.http.internal.OtlpProtoCommon.writeAttributes;

/**
 * Encodes a collection of {@link MetricData} as an OTLP {@code ExportMetricsServiceRequest} in the protobuf binary
 * format ({@code application/x-protobuf}).
 *
 * <p>Schema: <a href="https://github.com/open-telemetry/opentelemetry-proto/blob/main/opentelemetry/proto/metrics/v1/metrics.proto">metrics.proto</a>
 * and {@code collector/metrics/v1/metrics_service.proto}. Same coverage as {@link OtlpJsonMetricEncoder}: counters
 * and up-down counters as {@code sum}, gauges as {@code gauge} (long points as {@code as_int}, double points as
 * {@code as_double}), explicit-bucket histograms as {@code histogram}; a data point that does not match its
 * metric's kind is rejected with an {@link IllegalArgumentException}, as the JSON encoder rejects it.</p>
 */
public final class OtlpProtoMetricEncoder {

    private OtlpProtoMetricEncoder() {}

    public static byte[] encode(Collection<MetricData> metrics) {
        return OtlpProtoCommon.encodeRequest(metrics, MetricData::resource, MetricData::scope,
                OtlpProtoMetricEncoder::writeMetric);
    }

    /**
     * {@code Metric}: {@code name = 1}, {@code description = 2}, {@code unit = 3}, then one of {@code gauge = 5},
     * {@code sum = 7}, {@code histogram = 9}.
     */
    private static void writeMetric(ProtoWriter out, MetricData metric) {
        out.stringField(1, metric.name());
        out.stringField(2, metric.description());
        out.stringField(3, metric.unit());
        switch (metric.instrumentType()) {
            case COUNTER, UP_DOWN_COUNTER, OBSERVABLE_COUNTER, OBSERVABLE_UP_DOWN_COUNTER -> {
                // Sum: data_points = 1, aggregation_temporality = 2, is_monotonic = 3
                int mark = out.beginMessage(7);
                writeNumberDataPoints(out, metric);
                out.varintField(2, temporality(metric.temporality()));
                out.boolField(3, metric.monotonic());
                out.endMessage(mark);
            }
            case GAUGE, OBSERVABLE_GAUGE -> {
                // Gauge: data_points = 1
                int mark = out.beginMessage(5);
                writeNumberDataPoints(out, metric);
                out.endMessage(mark);
            }
            case HISTOGRAM -> {
                // Histogram: data_points = 1, aggregation_temporality = 2
                int mark = out.beginMessage(9);
                for (PointData point : metric.points()) {
                    if (!(point instanceof HistogramPointData histogramPoint)) {
                        throw unexpectedPoint(metric, point, "HistogramPointData");
                    }
                    writeHistogramDataPoint(out, histogramPoint);
                }
                out.varintField(2, temporality(metric.temporality()));
                out.endMessage(mark);
            }
            // Unreachable while every InstrumentType is listed above; a later one must not yield a metric without
            // data: it fails the batch, as a mismatched point does.
            default -> throw new IllegalArgumentException("metric '" + metric.name() + "' has the instrument type "
                    + metric.instrumentType() + ", which has no OTLP encoding");
        }
    }

    /**
     * {@code NumberDataPoint}: {@code start_time_unix_nano = 2}, {@code time_unix_nano = 3}, then one of
     * {@code as_double = 4} and {@code as_int = 6} (an {@code sfixed64}), {@code attributes = 7}.
     */
    private static void writeNumberDataPoints(ProtoWriter out, MetricData metric) {
        for (PointData point : metric.points()) {
            int mark = out.beginMessage(1);
            out.fixed64Field(2, point.startEpochNanos());
            out.fixed64Field(3, point.epochNanos());
            switch (point) {
                case LongPointData longPoint -> out.fixed64Always(6, longPoint.value());
                case DoublePointData doublePoint -> out.doubleAlways(4, doublePoint.value());
                default -> throw unexpectedPoint(metric, point, "LongPointData or DoublePointData");
            }
            writeAttributes(out, 7, point.attributes());
            out.endMessage(mark);
        }
    }

    /**
     * {@code HistogramDataPoint}: {@code start_time_unix_nano = 2}, {@code time_unix_nano = 3}, {@code count = 4},
     * {@code sum = 5}, {@code bucket_counts = 6} and {@code explicit_bounds = 7} (packed), {@code attributes = 9},
     * {@code min = 11} and {@code max = 12} when known (a NaN means none was recorded).
     */
    private static void writeHistogramDataPoint(ProtoWriter out, HistogramPointData point) {
        int mark = out.beginMessage(1);
        out.fixed64Field(2, point.startEpochNanos());
        out.fixed64Field(3, point.epochNanos());
        out.fixed64Field(4, point.count());
        out.doubleAlways(5, point.sum());
        if (!point.bucketCounts().isEmpty()) {
            int counts = out.beginMessage(6);
            point.bucketCounts().forEach(out::packedFixed64);
            out.endMessage(counts);
        }
        if (!point.boundaries().isEmpty()) {
            int bounds = out.beginMessage(7);
            point.boundaries().forEach(bound -> out.packedFixed64(Double.doubleToRawLongBits(bound)));
            out.endMessage(bounds);
        }
        writeAttributes(out, 9, point.attributes());
        if (!Double.isNaN(point.min())) {
            out.doubleAlways(11, point.min());
        }
        if (!Double.isNaN(point.max())) {
            out.doubleAlways(12, point.max());
        }
        out.endMessage(mark);
    }

    private static IllegalArgumentException unexpectedPoint(MetricData metric, PointData point, String expected) {
        return new IllegalArgumentException("metric '" + metric.name() + "' (" + metric.instrumentType()
                + ") carries a " + point.getClass().getSimpleName() + " data point; its OTLP encoding takes "
                + expected + " only");
    }

    /** {@code AggregationTemporality}: DELTA 1, CUMULATIVE 2. */
    private static int temporality(AggregationTemporality temporality) {
        return switch (temporality) {
            case DELTA -> 1;
            case CUMULATIVE -> 2;
        };
    }
}
