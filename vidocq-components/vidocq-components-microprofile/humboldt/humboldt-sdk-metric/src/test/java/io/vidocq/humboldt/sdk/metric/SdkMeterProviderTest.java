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
package io.vidocq.humboldt.sdk.metric;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.metric.data.HistogramPointData;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;
import io.vidocq.humboldt.sdk.metric.data.LongPointData;
import io.vidocq.humboldt.sdk.metric.data.MetricData;
import io.vidocq.humboldt.sdk.metric.export.InMemoryMetricExporter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkMeterProviderTest {

    private static final AttributeKey<String> ROUTE = AttributeKey.stringKey("http.route");

    @Test
    void counter_increments_visible_in_collect() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
        PeriodicMetricReader reader = PeriodicMetricReader.builder(exporter)
                .setInterval(Duration.ofMillis(30))
                .build();
        try (SdkMeterProvider p = SdkMeterProvider.builder()
                .setResource(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "humboldt-metric-test")))
                .registerMetricReader(reader)
                .build()) {
            Meter m = p.get("io.vidocq.test");
            LongCounter c = m.counterBuilder("http.requests")
                    .setDescription("HTTP request count")
                    .setUnit("1")
                    .build();
            c.add(3L, Attributes.of(ROUTE, "/a"));
            c.add(2L, Attributes.of(ROUTE, "/b"));
            c.add(5L, Attributes.of(ROUTE, "/a"));

            // Force a flush instead of waiting for the scheduleDelay
            p.flush().join(2, TimeUnit.SECONDS);
        }

        List<MetricData> all = exporter.getCollected();
        assertTrue(all.size() >= 1, "at least one MetricData expected");
        MetricData last = all.getLast();
        assertEquals("http.requests", last.name());
        assertEquals(InstrumentType.COUNTER, last.instrumentType());
        assertTrue(last.monotonic(), "Counter must be monotonic");
        assertEquals("HTTP request count", last.description());
        assertEquals("1", last.unit());

        // 2 distinct points per attribute set
        assertEquals(2, last.points().size());
        long sumA = last.points().stream()
                .filter(pt -> "/a".equals(((LongPointData) pt).attributes().get(ROUTE)))
                .mapToLong(pt -> ((LongPointData) pt).value()).sum();
        long sumB = last.points().stream()
                .filter(pt -> "/b".equals(((LongPointData) pt).attributes().get(ROUTE)))
                .mapToLong(pt -> ((LongPointData) pt).value()).sum();
        assertEquals(8L, sumA, "route=/a must accumulate 3+5");
        assertEquals(2L, sumB, "route=/b must accumulate 2");
    }

    @Test
    void counter_ignores_negative_values() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
        try (SdkMeterProvider p = SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(exporter)
                        .setInterval(Duration.ofSeconds(60)).build())
                .build()) {
            LongCounter c = p.get("x").counterBuilder("c").build();
            c.add(10L);
            c.add(-3L);
            c.add(5L);
            p.flush().join(2, TimeUnit.SECONDS);
        }
        MetricData m = exporter.getCollected().getLast();
        assertEquals(15L, ((LongPointData) m.points().getFirst()).value(),
                "negative value must be ignored (counter monotonic)");
    }

    @Test
    void histogram_records_distribute_across_buckets() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
        try (SdkMeterProvider p = SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(exporter)
                        .setInterval(Duration.ofSeconds(60)).build())
                .build()) {
            DoubleHistogram h = p.get("x").histogramBuilder("http.duration")
                    .setUnit("ms").build();
            h.record(2.5);    // bucket 0 (<= 0)? no -> bucket 1 (> 0, <= 5)
            h.record(15.0);   // bucket 3 (> 10, ≤ 25)
            h.record(150.0);  // bucket 7 (> 100, ≤ 250)
            h.record(7500.0); // bucket 13 (> 5000, ≤ 7500)
            p.flush().join(2, TimeUnit.SECONDS);
        }
        MetricData m = exporter.getCollected().getLast();
        assertEquals(InstrumentType.HISTOGRAM, m.instrumentType());
        HistogramPointData pt = (HistogramPointData) m.points().getFirst();
        assertEquals(4L, pt.count());
        assertEquals(2.5 + 15.0 + 150.0 + 7500.0, pt.sum(), 0.0001);
        assertEquals(2.5, pt.min(), 0.0001);
        assertEquals(7500.0, pt.max(), 0.0001);
        // 15 default boundaries → 16 buckets
        assertEquals(16, pt.bucketCounts().size());
    }

    @Test
    void resource_is_propagated_to_metric_data() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
        try (SdkMeterProvider p = SdkMeterProvider.builder()
                .setResource(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "svcX")))
                .registerMetricReader(PeriodicMetricReader.builder(exporter)
                        .setInterval(Duration.ofSeconds(60)).build())
                .build()) {
            p.get("x").counterBuilder("c").build().add(1L);
            p.flush().join(2, TimeUnit.SECONDS);
        }
        MetricData m = exporter.getCollected().getLast();
        assertEquals("svcX", m.resource().attributes().get(AttributeKey.stringKey("service.name")));
    }

    @Test
    void meter_cache_returns_same_instance_for_same_scope() {
        try (SdkMeterProvider p = SdkMeterProvider.builder().build()) {
            Meter a1 = p.get("scope-a");
            Meter a2 = p.get("scope-a");
            Meter b = p.get("scope-b");
            org.junit.jupiter.api.Assertions.assertSame(a1, a2);
            assertNotEquals(a1, b);
        }
    }

}
