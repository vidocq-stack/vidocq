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
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleUpDownCounter;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongGauge;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.common.Clock;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.DoublePointData;
import io.vidocq.humboldt.sdk.metric.data.HistogramPointData;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;
import io.vidocq.humboldt.sdk.metric.data.LongPointData;
import io.vidocq.humboldt.sdk.metric.data.MetricData;
import io.vidocq.humboldt.sdk.metric.export.MetricReader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises every instrument kind through the OpenTelemetry {@link Meter} API, collecting synchronously
 * through a {@link MetricReader} that keeps the provider's collection callback.
 */
class SdkMeterInstrumentsTest {

    private static final AttributeKey<String> KIND = AttributeKey.stringKey("kind");
    private static final Attributes A = Attributes.of(KIND, "a");
    private static final String DESCRIPTION = "a description";
    private static final String UNIT = "By";
    private static final String NAME = "instrument";
    private static final String OTHER = "other";
    private static final double DELTA = 1e-9;

    /** Reader that keeps the registration so that the test can collect on demand. */
    private static final class ManualReader implements MetricReader {
        private CollectionRegistration registration;
        private int shutdowns;

        @Override
        public void register(CollectionRegistration registration) {
            this.registration = registration;
        }

        List<MetricData> collect() {
            return List.copyOf(registration.collectAllMetrics());
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            shutdowns++;
            return CompletableResultCode.ofSuccess();
        }
    }

    private final ManualReader reader = new ManualReader();
    private final SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    private final Meter meter = provider.get("io.vidocq.test");

    private MetricData only() {
        List<MetricData> all = reader.collect();
        assertEquals(1, all.size());
        return all.getFirst();
    }

    private static long longValue(MetricData m) {
        assertEquals(1, m.points().size());
        return ((LongPointData) m.points().getFirst()).value();
    }

    private static double doubleValue(MetricData m) {
        assertEquals(1, m.points().size());
        return ((DoublePointData) m.points().getFirst()).value();
    }

    private static void assertShape(MetricData m, InstrumentType type, boolean monotonic) {
        assertEquals(NAME, m.name());
        assertEquals(DESCRIPTION, m.description());
        assertEquals(UNIT, m.unit());
        assertEquals(type, m.instrumentType());
        assertEquals(AggregationTemporality.CUMULATIVE, m.temporality());
        assertEquals(monotonic, m.monotonic());
    }

    @Test
    void long_counter_accepts_every_add_overload_and_null_attributes() {
        LongCounter c = meter.counterBuilder(NAME).setDescription(DESCRIPTION).setUnit(UNIT).build();
        c.add(1);
        c.add(2, null);
        c.add(3, Attributes.empty(), Context.root());
        MetricData m = only();
        assertShape(m, InstrumentType.COUNTER, true);
        assertEquals(6L, longValue(m));
    }

    @Test
    void null_description_and_unit_become_empty() {
        meter.counterBuilder(NAME).setDescription(null).setUnit(null).build().add(1);
        MetricData m = only();
        assertEquals("", m.description());
        assertEquals("", m.unit());
    }

    @Test
    void double_counter_keeps_description_and_unit_and_rejects_negative_and_nan() {
        DoubleCounter c = meter.counterBuilder(NAME).setDescription(DESCRIPTION).setUnit(UNIT)
                .ofDoubles().build();
        c.add(1.5);
        c.add(-1.0);
        c.add(Double.NaN);
        c.add(2.0, null);
        c.add(0.5, A, Context.root());
        List<MetricData> all = reader.collect();
        MetricData m = all.getFirst();
        assertShape(m, InstrumentType.COUNTER, true);
        double total = m.points().stream().mapToDouble(p -> ((DoublePointData) p).value()).sum();
        assertEquals(4.0, total, DELTA);
        assertEquals(2, m.points().size(), "one point per attribute set");
    }

    @Test
    void double_counter_builder_setters_override_the_long_builder_values() {
        meter.counterBuilder(NAME).ofDoubles().setDescription(DESCRIPTION).setUnit(UNIT).build().add(1.0);
        assertShape(only(), InstrumentType.COUNTER, true);
        meter.counterBuilder(OTHER).ofDoubles().setDescription(null).setUnit(null).build();
        assertTrue(reader.collect().stream().anyMatch(m -> OTHER.equals(m.name()) && m.unit().isEmpty()));
    }

    @Test
    void long_up_down_counter_accepts_negative_values() {
        LongUpDownCounter c = meter.upDownCounterBuilder(NAME).setDescription(DESCRIPTION).setUnit(UNIT).build();
        c.add(5);
        c.add(-7, null);
        c.add(1, Attributes.empty(), Context.root());
        MetricData m = only();
        assertShape(m, InstrumentType.UP_DOWN_COUNTER, false);
        assertEquals(-1L, longValue(m));
    }

    @Test
    void double_up_down_counter_inherits_description_and_unit_and_rejects_nan() {
        DoubleUpDownCounter c = meter.upDownCounterBuilder(NAME).setDescription(DESCRIPTION).setUnit(UNIT)
                .ofDoubles().build();
        c.add(2.5);
        c.add(-4.0, null);
        c.add(Double.NaN);
        c.add(0.5, Attributes.empty(), Context.root());
        MetricData m = only();
        assertShape(m, InstrumentType.UP_DOWN_COUNTER, false);
        assertEquals(-1.0, doubleValue(m), DELTA);
    }

    @Test
    void double_up_down_counter_setters_and_null_inherited_values() {
        meter.upDownCounterBuilder(NAME).setDescription(null).setUnit(null).ofDoubles()
                .setDescription(DESCRIPTION).setUnit(UNIT).build().add(1.0);
        assertShape(only(), InstrumentType.UP_DOWN_COUNTER, false);
        meter.upDownCounterBuilder(OTHER).ofDoubles().setDescription(null).setUnit(null).build();
        assertTrue(reader.collect().stream().anyMatch(m -> OTHER.equals(m.name()) && m.description().isEmpty()));
    }

    @Test
    void double_gauge_keeps_the_last_value() {
        DoubleGauge g = meter.gaugeBuilder(NAME).setDescription(DESCRIPTION).setUnit(UNIT).build();
        g.set(1.0);
        g.set(3.5, null);
        g.set(2.25, Attributes.empty(), Context.root());
        MetricData m = only();
        assertShape(m, InstrumentType.GAUGE, false);
        assertEquals(2.25, doubleValue(m), DELTA);
    }

    @Test
    void long_gauge_keeps_the_last_value_per_attribute_set() {
        LongGauge g = meter.gaugeBuilder(NAME).setDescription(DESCRIPTION).setUnit(UNIT).ofLongs().build();
        g.set(10);
        g.set(20, null);
        g.set(30, A, Context.root());
        MetricData m = reader.collect().getFirst();
        assertShape(m, InstrumentType.GAUGE, false);
        assertEquals(2, m.points().size());
        for (var p : m.points()) {
            LongPointData lp = (LongPointData) p;
            assertEquals(A.equals(lp.attributes()) ? 30L : 20L, lp.value());
        }
    }

    @Test
    void long_gauge_builder_setters() {
        meter.gaugeBuilder(NAME).ofLongs().setDescription(DESCRIPTION).setUnit(UNIT).build().set(1);
        assertShape(only(), InstrumentType.GAUGE, false);
        meter.gaugeBuilder(OTHER).setDescription(null).setUnit(null).ofLongs()
                .setDescription(null).setUnit(null).build();
        assertTrue(reader.collect().stream().anyMatch(m -> OTHER.equals(m.name()) && m.unit().isEmpty()));
    }

    @Test
    void double_histogram_uses_the_advised_boundaries_and_rejects_negative_and_nan() {
        DoubleHistogram h = meter.histogramBuilder(NAME).setDescription(DESCRIPTION).setUnit(UNIT)
                .setExplicitBucketBoundariesAdvice(List.of(1.0, 10.0))
                .build();
        h.record(0.5);
        h.record(5.0, null);
        h.record(50.0, Attributes.empty(), Context.root());
        h.record(-1.0);
        h.record(Double.NaN);
        MetricData m = only();
        assertShape(m, InstrumentType.HISTOGRAM, false);
        HistogramPointData p = (HistogramPointData) m.points().getFirst();
        assertEquals(List.of(1.0, 10.0), p.boundaries());
        assertEquals(List.of(1L, 1L, 1L), p.bucketCounts());
        assertEquals(3L, p.count());
        assertEquals(55.5, p.sum(), DELTA);
        assertEquals(0.5, p.min(), DELTA);
        assertEquals(50.0, p.max(), DELTA);
    }

    @Test
    void empty_or_null_boundary_advice_keeps_the_default_boundaries() {
        meter.histogramBuilder(NAME).setExplicitBucketBoundariesAdvice(List.of())
                .setExplicitBucketBoundariesAdvice(null).setDescription(null).setUnit(null).build().record(1.0);
        HistogramPointData p = (HistogramPointData) only().points().getFirst();
        assertEquals(16, p.bucketCounts().size());
    }

    @Test
    void long_histogram_inherits_description_and_unit_and_converts_boundaries() {
        LongHistogram h = meter.histogramBuilder(NAME).setDescription(DESCRIPTION).setUnit(UNIT).ofLongs()
                .setExplicitBucketBoundariesAdvice(List.of(10L, 100L))
                .build();
        h.record(5);
        h.record(50, null);
        h.record(500, Attributes.empty(), Context.root());
        h.record(-1);
        MetricData m = only();
        assertShape(m, InstrumentType.HISTOGRAM, false);
        HistogramPointData p = (HistogramPointData) m.points().getFirst();
        assertEquals(List.of(10.0, 100.0), p.boundaries());
        assertEquals(List.of(1L, 1L, 1L), p.bucketCounts());
        assertEquals(555.0, p.sum(), DELTA);
    }

    @Test
    void long_histogram_builder_setters_and_ignored_advice() {
        meter.histogramBuilder(NAME).setDescription(null).setUnit(null).ofLongs()
                .setDescription(DESCRIPTION).setUnit(UNIT)
                .setExplicitBucketBoundariesAdvice(List.of())
                .setExplicitBucketBoundariesAdvice(null)
                .build().record(1);
        MetricData m = only();
        assertShape(m, InstrumentType.HISTOGRAM, false);
        assertEquals(16, ((HistogramPointData) m.points().getFirst()).bucketCounts().size());
    }

    @Test
    void observable_gauges_report_the_value_observed_at_each_collection() {
        AtomicLong source = new AtomicLong(3);
        meter.gaugeBuilder("double.gauge").buildWithCallback(m -> m.record(source.get() / 2.0));
        meter.gaugeBuilder("long.gauge").ofLongs().buildWithCallback(m -> m.record(source.get(), A));
        assertCollected(InstrumentType.OBSERVABLE_GAUGE, 1.5, 3L);
        source.set(8);
        assertCollected(InstrumentType.OBSERVABLE_GAUGE, 4.0, 8L);
    }

    private void assertCollected(InstrumentType type, double doubleValue, long longValue) {
        List<MetricData> all = reader.collect();
        assertEquals(2, all.size());
        for (MetricData m : all) {
            assertEquals(type, m.instrumentType());
            if (m.name().startsWith("double")) {
                assertEquals(doubleValue, doubleValue(m), DELTA);
            } else {
                assertEquals(longValue, longValue(m));
            }
        }
    }

    @Test
    void observable_counters_and_up_down_counters_have_their_observable_types() {
        meter.counterBuilder("c.long").buildWithCallback(m -> m.record(1));
        meter.counterBuilder("c.double").ofDoubles().buildWithCallback(m -> m.record(1.0));
        meter.upDownCounterBuilder("u.long").buildWithCallback(m -> m.record(-1, A));
        meter.upDownCounterBuilder("u.double").ofDoubles().buildWithCallback(m -> m.record(-1.0, A));
        List<MetricData> all = reader.collect();
        assertEquals(4, all.size());
        for (MetricData m : all) {
            boolean counter = m.name().startsWith("c.");
            assertEquals(counter ? InstrumentType.OBSERVABLE_COUNTER : InstrumentType.OBSERVABLE_UP_DOWN_COUNTER,
                    m.instrumentType());
            assertEquals(counter, m.monotonic());
            assertEquals(1, m.points().size());
        }
    }

    @Test
    void a_failing_observable_callback_does_not_break_the_collection() {
        meter.gaugeBuilder("broken").buildWithCallback(m -> {
            throw new IllegalStateException("callback failure");
        });
        meter.gaugeBuilder("healthy").ofLongs().buildWithCallback(m -> m.record(7));
        List<MetricData> all = reader.collect();
        assertEquals(2, all.size());
        MetricData healthy = all.stream().filter(m -> "healthy".equals(m.name())).findFirst().orElseThrow();
        assertEquals(7L, longValue(healthy));
        MetricData broken = all.stream().filter(m -> "broken".equals(m.name())).findFirst().orElseThrow();
        assertTrue(broken.points().isEmpty());
    }

    @Test
    void observers_record_into_their_instrument() {
        ObservableLongMeasurement longCounter = meter.counterBuilder("o.c.long").buildObserver();
        ObservableDoubleMeasurement doubleCounter = meter.counterBuilder("o.c.double").ofDoubles().buildObserver();
        ObservableLongMeasurement longUpDown = meter.upDownCounterBuilder("o.u.long").buildObserver();
        ObservableDoubleMeasurement doubleUpDown = meter.upDownCounterBuilder("o.u.double").ofDoubles()
                .buildObserver();
        ObservableDoubleMeasurement doubleGauge = meter.gaugeBuilder("o.g.double").buildObserver();
        ObservableLongMeasurement longGauge = meter.gaugeBuilder("o.g.long").ofLongs().buildObserver();
        longCounter.record(2);
        doubleCounter.record(2.5, A);
        longUpDown.record(-3, null);
        doubleUpDown.record(-3.5);
        doubleGauge.record(9.5, null);
        longGauge.record(9, A);

        List<MetricData> all = reader.collect();
        assertEquals(6, all.size());
        for (MetricData m : all) {
            assertEquals(1, m.points().size(), m.name());
            double v = switch (m.points().getFirst()) {
                case LongPointData lp -> lp.value();
                case DoublePointData dp -> dp.value();
                case HistogramPointData hp -> hp.sum();
            };
            double expected = switch (m.name()) {
                case "o.c.long" -> 2;
                case "o.c.double" -> 2.5;
                case "o.u.long" -> -3;
                case "o.u.double" -> -3.5;
                case "o.g.double" -> 9.5;
                default -> 9;
            };
            assertEquals(expected, v, DELTA, m.name());
        }
    }

    @Test
    void meter_builder_ignores_version_and_schema_and_shares_the_meter() {
        Meter built = provider.meterBuilder("io.vidocq.test")
                .setInstrumentationVersion("1.0")
                .setSchemaUrl("https://example.org/schema")
                .build();
        assertSame(meter, built);
        assertSame(provider.get(""), provider.get(null));
    }

    @Test
    void collection_uses_the_provider_clock_and_start_time() {
        AtomicLong now = new AtomicLong(1_000L);
        Clock clock = new Clock() {
            @Override
            public long now() {
                return now.get();
            }

            @Override
            public long nanoTime() {
                return now.get();
            }
        };
        ManualReader manual = new ManualReader();
        SdkMeterProvider p = SdkMeterProvider.builder()
                .setClock(clock)
                .setClock(null)
                .setResource(null)
                .registerMetricReader(null)
                .registerMetricReader(manual)
                .build();
        p.get("clock").counterBuilder(NAME).build().add(1);
        now.set(5_000L);
        LongPointData point = (LongPointData) manual.collect().getFirst().points().getFirst();
        assertEquals(1_000L, point.startEpochNanos());
        assertEquals(5_000L, point.epochNanos());
        assertEquals(Resource.empty(), p.getResource());
        assertTrue(p.flush().isSuccess());
        p.close();
        assertEquals(1, manual.shutdowns);
    }

    @Test
    void a_provider_without_reader_flushes_and_shuts_down_successfully() {
        SdkMeterProvider p = SdkMeterProvider.builder().build();
        assertTrue(p.flush().isSuccess());
        assertTrue(p.shutdown().isSuccess());
        assertEquals(Resource.empty(), p.getResource());
    }
}
