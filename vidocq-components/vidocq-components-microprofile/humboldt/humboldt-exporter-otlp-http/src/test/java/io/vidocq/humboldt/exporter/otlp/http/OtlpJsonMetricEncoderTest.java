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
package io.vidocq.humboldt.exporter.otlp.http;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpJsonMetricEncoder;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.DoublePointData;
import io.vidocq.humboldt.sdk.metric.data.HistogramPointData;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;
import io.vidocq.humboldt.sdk.metric.data.LongPointData;
import io.vidocq.humboldt.sdk.metric.data.MetricData;
import io.vidocq.humboldt.sdk.metric.data.PointData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OTLP/JSON mapping of the metric data humboldt-sdk-metric produces, checked against the OpenTelemetry proto
 * JSON encoding (and the OpenTelemetry Java 1.66 JSON marshalers): sums and gauges carry {@code NumberDataPoint}s
 * whose value is {@code "asInt"} (an int64, hence a JSON string) or {@code "asDouble"} (a JSON number, or
 * {@code "NaN"}/{@code "Infinity"}/{@code "-Infinity"}); a sum adds {@code aggregationTemporality} (enum as an
 * integer) and {@code isMonotonic}; a gauge has its data points only (BUG-20261004-02).
 */
class OtlpJsonMetricEncoderTest {

    private static final String POINT_TIMES = "\"startTimeUnixNano\":\"1\",\"timeUnixNano\":\"2\"";

    @Test
    void encodes_long_sum_points_as_asInt_strings() {
        String json = encode(InstrumentType.COUNTER, true, new LongPointData(1L, 2L, Attributes.empty(), 7L));

        assertTrue(json.contains("{\"name\":\"m\",\"sum\":{\"dataPoints\":[{" + POINT_TIMES + ",\"asInt\":\"7\"}],"
                + "\"aggregationTemporality\":2,\"isMonotonic\":true}}"), json);
    }

    @Test
    void encodes_double_counter_points_as_asDouble() {
        String json = encode(InstrumentType.COUNTER, true, new DoublePointData(1L, 2L, Attributes.empty(), 2.5));

        assertTrue(json.contains("{\"name\":\"m\",\"sum\":{\"dataPoints\":[{" + POINT_TIMES + ",\"asDouble\":2.5}],"
                + "\"aggregationTemporality\":2,\"isMonotonic\":true}}"), json);
    }

    @Test
    void encodes_double_up_down_counter_points_as_a_non_monotonic_sum() {
        String json = encode(InstrumentType.UP_DOWN_COUNTER, false,
                new DoublePointData(1L, 2L, Attributes.empty(), -1.25));

        assertTrue(json.contains("{\"name\":\"m\",\"sum\":{\"dataPoints\":[{" + POINT_TIMES + ",\"asDouble\":-1.25}],"
                + "\"aggregationTemporality\":2,\"isMonotonic\":false}}"), json);
    }

    @Test
    void encodes_observable_double_sum_points() {
        String json = encode(InstrumentType.OBSERVABLE_COUNTER, true,
                new DoublePointData(1L, 2L, Attributes.empty(), 3.0));

        assertTrue(json.contains("\"sum\":{\"dataPoints\":[{" + POINT_TIMES + ",\"asDouble\":3.0}]"), json);
    }

    @Test
    void encodes_a_synchronous_long_gauge_as_a_gauge() {
        String json = encode(InstrumentType.GAUGE, false, new LongPointData(1L, 2L, Attributes.empty(), 42L));

        assertTrue(json.contains("{\"name\":\"m\",\"gauge\":{\"dataPoints\":[{"
                        + POINT_TIMES + ",\"asInt\":\"42\"}]}}"),
                json);
    }

    @Test
    void encodes_a_synchronous_double_gauge_as_a_gauge() {
        String json = encode(InstrumentType.GAUGE, false, new DoublePointData(1L, 2L, Attributes.empty(), 0.75));

        assertTrue(json.contains("{\"name\":\"m\",\"gauge\":{\"dataPoints\":[{"
                        + POINT_TIMES + ",\"asDouble\":0.75}]}}"),
                json);
    }

    @Test
    void encodes_observable_double_gauge_points() {
        String json = encode(InstrumentType.OBSERVABLE_GAUGE, false,
                new DoublePointData(1L, 2L, Attributes.empty(), 21.5));

        assertTrue(json.contains("{\"name\":\"m\",\"gauge\":{\"dataPoints\":[{"
                        + POINT_TIMES + ",\"asDouble\":21.5}]}}"),
                json);
    }

    @Test
    void encodes_non_finite_double_points_as_json_strings() {
        String json = encode(InstrumentType.GAUGE, false,
                new DoublePointData(1L, 2L, Attributes.empty(), Double.NaN),
                new DoublePointData(1L, 2L, Attributes.of(AttributeKey.stringKey("k"), "v"), Double.NEGATIVE_INFINITY));

        assertTrue(json.contains("\"asDouble\":\"NaN\""), json);
        assertTrue(json.contains("\"asDouble\":\"-Infinity\",\"attributes\":[{\"key\":\"k\""), json);
    }

    @ParameterizedTest
    @EnumSource(value = InstrumentType.class, names = {"COUNTER", "OBSERVABLE_UP_DOWN_COUNTER", "GAUGE"})
    void rejects_a_histogram_point_in_a_sum_or_a_gauge_instead_of_dropping_it(InstrumentType type) {
        HistogramPointData histogramPoint = new HistogramPointData(
                1L, 2L, Attributes.empty(), 3.0, 1L, 3.0, 3.0, List.of(10.0), List.of(1L, 0L));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> encode(type, true, new LongPointData(1L, 2L, Attributes.empty(), 7L), histogramPoint));

        assertTrue(e.getMessage().contains("'m'") && e.getMessage().contains(type.name())
                && e.getMessage().contains("HistogramPointData"), e.getMessage());
    }

    @Test
    void rejects_a_number_point_in_a_histogram_instead_of_dropping_it() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> encode(InstrumentType.HISTOGRAM, false, new DoublePointData(1L, 2L, Attributes.empty(), 2.5)));

        assertTrue(e.getMessage().contains("'m'") && e.getMessage().contains("HISTOGRAM")
                && e.getMessage().contains("DoublePointData"), e.getMessage());
    }

    private static String encode(InstrumentType type, boolean monotonic, PointData... points) {
        MetricData metric = new MetricData(
                Resource.empty(), InstrumentationScope.of("x"), "m", "", "",
                type, AggregationTemporality.CUMULATIVE, monotonic, List.of(points));
        return OtlpJsonMetricEncoder.encode(List.of(metric));
    }

    @Test
    void encodes_non_finite_histogram_doubles_as_json_strings() {
        HistogramPointData point = new HistogramPointData(
                1L, 2L, Attributes.empty(),
                Double.POSITIVE_INFINITY, 2L, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                List.of(10.0), List.of(1L, 1L));
        MetricData histogram = new MetricData(
                Resource.empty(), InstrumentationScope.of("x"), "latency", "", "ms",
                InstrumentType.HISTOGRAM, AggregationTemporality.CUMULATIVE, false, List.of(point));

        String json = OtlpJsonMetricEncoder.encode(List.of(histogram));

        assertTrue(json.contains("\"sum\":\"Infinity\""), json);
        assertTrue(json.contains("\"min\":\"-Infinity\""), json);
        assertTrue(json.contains("\"max\":\"Infinity\""), json);
        assertTrue(json.contains("\"explicitBounds\":[10.0]"), json);
    }
}
