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
package io.vidocq.humboldt.otel.interop;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.metric.data.DoublePointData;
import io.vidocq.humboldt.sdk.metric.data.HistogramPointData;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;
import io.vidocq.humboldt.sdk.metric.data.LongPointData;
import io.vidocq.humboldt.sdk.metric.data.MetricData;
import io.vidocq.humboldt.sdk.metric.data.PointData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricDataMapperTest {

    private static final Attributes ATTRS = Attributes.of(AttributeKey.stringKey("k"), "v");
    private static final Resource RESOURCE =
            Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "svc"));
    private static final InstrumentationScope SCOPE =
            new InstrumentationScope("my.scope", "1.2.3", null, Attributes.empty());

    private static MetricData metric(InstrumentType type,
                                     io.vidocq.humboldt.sdk.metric.data.AggregationTemporality temporality,
                                     boolean monotonic, List<? extends PointData> points) {
        return new MetricData(RESOURCE, SCOPE, "requests", "the requests", "1",
                type, temporality, monotonic, points);
    }

    private static MetricData cumulative(InstrumentType type, List<? extends PointData> points) {
        return metric(type, io.vidocq.humboldt.sdk.metric.data.AggregationTemporality.CUMULATIVE, true, points);
    }

    @Test
    void long_counter_becomes_a_long_sum() {
        var out = MetricDataMapper.toOtel(cumulative(InstrumentType.COUNTER,
                List.of(new LongPointData(10L, 20L, ATTRS, 42L))));

        assertEquals(MetricDataType.LONG_SUM, out.getType());
        assertEquals("requests", out.getName());
        assertEquals("the requests", out.getDescription());
        assertEquals("1", out.getUnit());
        assertEquals("my.scope", out.getInstrumentationScopeInfo().getName());
        assertEquals("1.2.3", out.getInstrumentationScopeInfo().getVersion());
        assertEquals("svc", out.getResource().getAttribute(AttributeKey.stringKey("service.name")));
        assertNull(out.getResource().getSchemaUrl());
        assertTrue(out.getLongSumData().isMonotonic());
        assertEquals(AggregationTemporality.CUMULATIVE, out.getLongSumData().getAggregationTemporality());
        var point = out.getLongSumData().getPoints().iterator().next();
        assertEquals(10L, point.getStartEpochNanos());
        assertEquals(20L, point.getEpochNanos());
        assertEquals(ATTRS, point.getAttributes());
        assertEquals(42L, point.getValue());
    }

    @Test
    void double_up_down_counter_becomes_a_delta_double_sum() {
        var out = MetricDataMapper.toOtel(metric(InstrumentType.UP_DOWN_COUNTER,
                io.vidocq.humboldt.sdk.metric.data.AggregationTemporality.DELTA, false,
                List.of(new DoublePointData(1L, 2L, ATTRS, 1.5))));

        assertEquals(MetricDataType.DOUBLE_SUM, out.getType());
        assertFalse(out.getDoubleSumData().isMonotonic());
        assertEquals(AggregationTemporality.DELTA, out.getDoubleSumData().getAggregationTemporality());
        assertEquals(1.5, out.getDoubleSumData().getPoints().iterator().next().getValue());
    }

    @Test
    void sum_keeps_only_points_of_the_type_of_the_first_point() {
        List<PointData> mixed = new ArrayList<>();
        mixed.add(new DoublePointData(1L, 2L, ATTRS, 1.0));
        mixed.add(new LongPointData(1L, 2L, ATTRS, 7L));
        var doubles = MetricDataMapper.toOtel(cumulative(InstrumentType.OBSERVABLE_COUNTER, mixed));
        assertEquals(1, doubles.getDoubleSumData().getPoints().size());

        List<PointData> longFirst = new ArrayList<>();
        longFirst.add(new LongPointData(1L, 2L, ATTRS, 7L));
        longFirst.add(new DoublePointData(1L, 2L, ATTRS, 1.0));
        var longs = MetricDataMapper.toOtel(cumulative(InstrumentType.OBSERVABLE_UP_DOWN_COUNTER, longFirst));
        assertEquals(1, longs.getLongSumData().getPoints().size());
    }

    @Test
    void empty_sum_is_a_long_sum_without_points() {
        var out = MetricDataMapper.toOtel(cumulative(InstrumentType.COUNTER, List.of()));
        assertEquals(MetricDataType.LONG_SUM, out.getType());
        assertTrue(out.getLongSumData().getPoints().isEmpty());
    }

    @Test
    void histogram_keeps_buckets_and_min_max() {
        var hp = new HistogramPointData(5L, 6L, ATTRS, 12.0, 3L, 1.0, 8.0,
                List.of(5.0), List.of(2L, 1L));
        var out = MetricDataMapper.toOtel(cumulative(InstrumentType.HISTOGRAM,
                List.of(hp, new LongPointData(1L, 2L, ATTRS, 1L))));

        assertEquals(MetricDataType.HISTOGRAM, out.getType());
        var points = out.getHistogramData().getPoints();
        assertEquals(1, points.size());
        var point = points.iterator().next();
        assertEquals(12.0, point.getSum());
        assertEquals(3L, point.getCount());
        assertTrue(point.hasMin());
        assertEquals(1.0, point.getMin());
        assertTrue(point.hasMax());
        assertEquals(8.0, point.getMax());
        assertEquals(List.of(5.0), point.getBoundaries());
        assertEquals(List.of(2L, 1L), point.getCounts());
    }

    @Test
    void histogram_without_recorded_values_has_no_min_max() {
        var hp = new HistogramPointData(5L, 6L, ATTRS, 0.0, 0L, Double.NaN, Double.NaN,
                List.of(), List.of(0L));
        var point = MetricDataMapper.toOtel(cumulative(InstrumentType.HISTOGRAM, List.of(hp)))
                .getHistogramData().getPoints().iterator().next();
        assertFalse(point.hasMin());
        assertFalse(point.hasMax());
    }

    @Test
    void double_gauge_becomes_a_double_gauge() {
        List<PointData> pts = new ArrayList<>();
        pts.add(new DoublePointData(1L, 2L, ATTRS, 0.25));
        pts.add(new LongPointData(1L, 2L, ATTRS, 3L));
        var out = MetricDataMapper.toOtel(cumulative(InstrumentType.GAUGE, pts));
        assertEquals(MetricDataType.DOUBLE_GAUGE, out.getType());
        assertEquals(1, out.getDoubleGaugeData().getPoints().size());
        assertEquals(0.25, out.getDoubleGaugeData().getPoints().iterator().next().getValue());
    }

    @Test
    void long_gauge_becomes_a_long_gauge() {
        List<PointData> pts = new ArrayList<>();
        pts.add(new LongPointData(1L, 2L, ATTRS, 3L));
        pts.add(new DoublePointData(1L, 2L, ATTRS, 0.25));
        var out = MetricDataMapper.toOtel(cumulative(InstrumentType.OBSERVABLE_GAUGE, pts));
        assertEquals(MetricDataType.LONG_GAUGE, out.getType());
        assertEquals(1, out.getLongGaugeData().getPoints().size());
        assertEquals(3L, out.getLongGaugeData().getPoints().iterator().next().getValue());
    }

    @Test
    void resource_schema_url_is_kept() {
        var withSchema = new MetricData(
                Resource.create(Attributes.empty(), "https://opentelemetry.io/schemas/1.27.0"),
                SCOPE, "g", null, null, InstrumentType.GAUGE, null, false, List.of());
        var out = MetricDataMapper.toOtel(withSchema);
        assertEquals("https://opentelemetry.io/schemas/1.27.0", out.getResource().getSchemaUrl());
        assertEquals(MetricDataType.LONG_GAUGE, out.getType());
    }

    @Test
    void toOtelAll_maps_every_metric() {
        var out = MetricDataMapper.toOtelAll(List.of(
                cumulative(InstrumentType.COUNTER, List.of(new LongPointData(1L, 2L, ATTRS, 1L))),
                cumulative(InstrumentType.GAUGE, List.of(new DoublePointData(1L, 2L, ATTRS, 2.0)))));
        assertEquals(2, out.size());
        assertEquals(MetricDataType.LONG_SUM, out.get(0).getType());
        assertEquals(MetricDataType.DOUBLE_GAUGE, out.get(1).getType());
    }
}
