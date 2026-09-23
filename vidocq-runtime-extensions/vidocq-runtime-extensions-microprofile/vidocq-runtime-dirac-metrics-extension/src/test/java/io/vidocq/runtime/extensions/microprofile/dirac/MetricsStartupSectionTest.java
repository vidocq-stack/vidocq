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
import io.vidocq.runtime.spi.report.Verbosity;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code metrics} section of the startup report: the boot facts of the panel. */
class MetricsStartupSectionTest {

    private final MetricRegistryProducerBean producer = new MetricRegistryProducerBean();
    private final MetricRegistry application = producer.produceApplicationByType();
    private final AtomicInteger gaugeCalls = new AtomicInteger();

    private RecordingSection write(Verbosity verbosity) {
        application.counter("orders.placed");
        application.timer("checkout", new Tag("shop", "eu"));
        application.gauge("stock", () -> {
            gaugeCalls.incrementAndGet();
            return 3;
        }, new Tag("shop", "eu"));
        RecordingSection section = new RecordingSection();
        MetricsStartupSection.write(DiracRegistries.of(producer), null, new FakeReportContext(verbosity), section);
        return section;
    }

    private int baseMetrics() {
        return producer.produceBaseByType().getMetricIDs().size();
    }

    @Test
    void theSummaryCountsTheMetricsOfEveryRegistry() {
        RecordingSection section = write(Verbosity.SUMMARY);

        assertEquals("3 application metrics, " + baseMetrics() + " base, 0 vendor", section.summary);
        assertTrue(section.rows.isEmpty());
        assertTrue(section.lists.isEmpty());
    }

    @Test
    void aRowPerRegistryCountsItsMetricsByKind() {
        RecordingSection section = write(Verbosity.DETAILED);

        assertEquals(List.of("application", "base", "vendor"), List.copyOf(section.rows.keySet()));
        assertEquals("1 counter, 1 timer, 0 histograms, 1 gauge", section.rows.get("application"));
        assertEquals("0 counters, 0 timers, 0 histograms, 0 gauges", section.rows.get("vendor"));
    }

    @Test
    void theApplicationMetricsAreListedWithTheKeysThePanelShowsThemUnder() {
        RecordingSection section = write(Verbosity.DETAILED);

        assertEquals(List.of("orders.placed = counter orders.placed",
                        "checkout.shop-eu, checkout.shop-eu.mean = timer checkout{shop=eu}"),
                section.lists.get("application metrics"));
    }

    @Test
    void gaugesAreListedByNameAndNeverCalled() {
        RecordingSection section = write(Verbosity.DETAILED);

        assertEquals(List.of("stock{shop=eu}"), section.lists.get("application gauges"));
        assertEquals(baseMetrics(), section.lists.get("base gauges").size());
        assertEquals(0, gaugeCalls.get(), "the report called application code");
    }

    @Test
    void anEmptyRegistryListsNothing() {
        RecordingSection section = write(Verbosity.DETAILED);

        assertFalse(section.lists.containsKey("vendor metrics"));
        assertFalse(section.lists.containsKey("vendor gauges"));
        assertFalse(section.lists.containsKey("base metrics"));
    }

    @Test
    void registriesNotCreatedYetSayWhy() {
        RecordingSection section = new RecordingSection();

        MetricsStartupSection.write(null, DiracLiveBean.NOT_CREATED_YET, new FakeReportContext(Verbosity.DETAILED),
                section);

        assertEquals("registries not created yet", section.summary);
        assertTrue(section.rows.isEmpty());
        assertNull(section.lists.get("application metrics"));
    }
}
