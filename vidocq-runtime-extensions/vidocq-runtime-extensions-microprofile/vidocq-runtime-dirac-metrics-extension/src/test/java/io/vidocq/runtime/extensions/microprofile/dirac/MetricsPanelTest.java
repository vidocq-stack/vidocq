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
package io.vidocq.runtime.extensions.microprofile.dirac;

import io.vidocq.dirac.cdi.internal.MetricRegistryProducerBean;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.Timer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What one sample of the {@code metrics} panel writes, from Dirac's real registries. */
class MetricsPanelTest {

    private final MetricRegistryProducerBean producer = new MetricRegistryProducerBean();
    private final MetricRegistry application = producer.produceApplicationByType();

    private RecordingSample sample() {
        RecordingSample out = new RecordingSample();
        MetricsPanel.write(DiracRegistries.of(producer), out);
        return out;
    }

    @Test
    void oneGroupPerRegistryApplicationFirst() {
        assertEquals(List.of("application", "base", "vendor"), List.copyOf(sample().groups().keySet()));
    }

    @Test
    void everyGroupCountsItsMetricsByKind() {
        application.counter("orders");
        application.timer("checkout");
        application.histogram("basket.size");
        application.gauge("stock", () -> 3);

        RecordingSample app = sample().groups().get("application");

        assertEquals(1, app.number("counters"));
        assertEquals(1, app.number("timers"));
        assertEquals(1, app.number("histograms"));
        assertEquals(1, app.number("gauges"));
        RecordingSample base = sample().groups().get("base");
        assertEquals(producer.produceBaseByType().getGauges().size(), base.number("gauges"));
    }

    @Test
    void anApplicationCounterIncrementedMovesInThePanel() {
        Counter orders = application.counter("orders.placed", new Tag("shop", "eu"));
        orders.inc();

        RecordingSample first = sample().groups().get("application");
        orders.inc(2);
        RecordingSample second = sample().groups().get("application");

        assertEquals("counter", first.kind("orders.placed.shop-eu"));
        assertEquals(1, first.number("orders.placed.shop-eu"));
        assertEquals(3, second.number("orders.placed.shop-eu"));
    }

    @Test
    void aTimerShowsItsCountAndItsMeanButNoMax() {
        Timer checkout = application.timer("checkout");
        checkout.update(Duration.ofMillis(10));
        checkout.update(Duration.ofMillis(30));

        RecordingSample app = sample().groups().get("application");

        assertEquals("counter", app.kind("checkout"));
        assertEquals(2, app.number("checkout"));
        assertEquals("duration", app.kind("checkout.mean"));
        assertEquals(Duration.ofMillis(20).toString(), app.text("checkout.mean"));
        assertTrue(app.keys().stream().noneMatch(key -> key.startsWith("checkout.max")), app.keys().toString());
    }

    @Test
    void aTimerNeverCalledHasNoMean() {
        application.timer("checkout");

        RecordingSample app = sample().groups().get("application");

        assertEquals(0, app.number("checkout"));
        assertEquals("absent", app.kind("checkout.mean"));
        assertEquals(MetricsPanel.NO_CALL_YET, app.text("checkout.mean"));
    }

    @Test
    void aHistogramShowsHowManyValuesItRecorded() {
        application.histogram("basket.size").update(4);
        application.histogram("basket.size").update(6);

        assertEquals(2, sample().groups().get("application").number("basket.size"));
    }

    @Test
    void aGaugeIsCountedButNeverCalled() {
        AtomicInteger calls = new AtomicInteger();
        application.gauge("stock", () -> {
            calls.incrementAndGet();
            return 3;
        });

        RecordingSample app = sample().groups().get("application");

        assertEquals(0, calls.get(), "sampling called application code");
        assertEquals(1, app.number("gauges"));
        assertTrue(app.keys().stream().noneMatch(key -> key.startsWith("stock")), app.keys().toString());
    }

    @Test
    void aGroupKeepsWithinTheConsoleLimitAndSaysHowManyMetricsItLeftOut() {
        for (int i = 0; i < 70; i++) {
            application.counter(String.format("c%02d", i));
        }

        RecordingSample app = sample().groups().get("application");

        assertTrue(app.keys().size() <= 64, "more than 64 values: " + app.keys().size());
        assertEquals("counter", app.kind("c58"));
        assertEquals(null, app.kind("c59"));
        assertEquals("text", app.kind("omitted"));
        assertEquals("11 metrics left out: the console shows 64 values per group", app.text("omitted"));
    }

    @Test
    void aTimerThatDoesNotFitWhollyIsLeftOutWithEverythingAfterIt() {
        for (int i = 0; i < 58; i++) {
            application.counter(String.format("c%02d", i));
        }
        application.timer("checkout");
        application.histogram("sizes");

        RecordingSample app = sample().groups().get("application");

        assertEquals("counter", app.kind("c57"));
        assertEquals(null, app.kind("checkout"));
        assertEquals(null, app.kind("sizes"));
        assertEquals("2 metrics left out: the console shows 64 values per group", app.text("omitted"));
    }

    @Test
    void nothingIsLeftOutWhenEverythingFits() {
        application.counter("orders");

        assertEquals(null, sample().groups().get("application").kind("omitted"));
    }
}
