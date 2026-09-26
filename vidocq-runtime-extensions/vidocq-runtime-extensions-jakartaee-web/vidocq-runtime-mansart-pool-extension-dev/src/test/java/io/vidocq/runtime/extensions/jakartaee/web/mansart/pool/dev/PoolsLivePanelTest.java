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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import io.vidocq.mansart.pool.core.MansartDataSource;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.MansartPoolExtension;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev.RecordedSample.Absent;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev.RecordedSample.Counter;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev.RecordedSample.Elapsed;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev.RecordedSample.Gauge;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.live.MansartPoolsLive;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.Series;
import io.vidocq.runtime.spi.devconsole.Unit;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The {@code mansart-pool} section, live: {@link PoolsLivePanel} reads {@link MansartPoolsLive}, the holder
 * {@link MansartPoolExtension} publishes once every pool is open and clears first thing in its {@code onStop}
 * (Vidocq/vidocq#143).
 */
class PoolsLivePanelTest {

    private final MansartPoolExtension ext = new MansartPoolExtension();
    private final PoolsLivePanel panel = new PoolsLivePanel();

    @AfterEach
    void stop() {
        ext.onStop();
    }

    private static String h2(String name) {
        return "jdbc:h2:mem:live-" + name + "-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    /** A {@link VidocqConfiguration} backed by a map. */
    private record MapConfig(Map<String, String> data) implements VidocqConfiguration {
        @Override
        public Optional<String> property(String key) {
            return Optional.ofNullable(data.get(key));
        }

        @Override
        public Iterable<String> propertyNames() {
            return data.keySet();
        }
    }

    private static MapConfig config(String... keysAndValues) {
        Map<String, String> data = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            data.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return new MapConfig(data);
    }

    /** Configures the extension and opens its pools, as Vidocq does before the container starts: publishes them. */
    private void boot(String... keysAndValues) {
        ext.configure(config(keysAndValues));
        ext.beforeStart(new VaubanContainerBuilder());
    }

    private RecordedSample sample() {
        RecordedSample sample = new RecordedSample();
        panel.sample(sample);
        return sample;
    }

    /** The open {@code @Default} pool, read straight from the holder the panel itself reads. */
    private MansartDataSource defaultPool() {
        return MansartPoolsLive.pools().stream()
                .filter(p -> p.label().equals("@Default"))
                .findFirst().orElseThrow()
                .pool();
    }

    @Test
    void itMakesThePoolSectionLiveWithItsCharts() {
        assertEquals("mansart-pool", panel.id());
        assertEquals(List.of(
                        new Chart("connections", "Connections", List.of(Series.area("active"), Series.stacked("idle"),
                                Series.line("waiting"), Series.ceiling("active"))),
                        new Chart("throughput", "Throughput",
                                List.of(Series.rate("borrows"), Series.rate("timeouts")))),
                panel.charts());
    }

    @Test
    void afterTheHolderIsClearedTheSampleWritesNoGroup() {
        MansartPoolsLive.clear();

        assertEquals(List.of(), sample().groupNames(), "a dev reload never shows the previous boot's pools");
    }

    @Test
    void eachPoolIsAGroupOfLiveValues() throws Exception {
        boot("vidocq.pool.url", h2("default"), "vidocq.pool.maxSize", "8",
                "vidocq.pool.audit.url", h2("audit"), "vidocq.pool.audit.maxSize", "4",
                "vidocq.pool.audit.leakDetectionThreshold", "PT30S");

        RecordedSample held;
        try (Connection borrowed = defaultPool().getConnection()) {
            assertNotNull(borrowed);
            held = sample();
        }
        RecordedSample released = sample();

        assertEquals(List.of(), held.keys(), "every value belongs to a pool");
        assertEquals(List.of("@Default", "audit"), held.groupNames());
        RecordedSample busy = held.written("@Default");
        assertEquals(List.of("active", "idle", "waiting", "borrows", "timeouts", "leaks", "mean-borrow"), busy.keys());
        assertEquals(new Gauge(1, 8.0, Unit.COUNT), busy.value("active"));
        assertEquals(new Gauge(0, 8.0, Unit.COUNT), busy.value("idle"));
        assertEquals(new Gauge(0, null, Unit.COUNT), busy.value("waiting"), "an estimate, with no max");
        assertEquals(new Counter(1, Unit.COUNT), busy.value("borrows"));
        assertEquals(new Counter(0, Unit.COUNT), busy.value("timeouts"));
        assertEquals(new Absent("leak detection off"), busy.value("leaks"), "off, never a zero");
        Elapsed mean = assertInstanceOf(Elapsed.class, busy.value("mean-borrow"));
        assertFalse(mean.value().isNegative());

        RecordedSample idle = released.written("@Default");
        assertEquals(new Gauge(0, 8.0, Unit.COUNT), idle.value("active"));
        assertEquals(new Gauge(1, 8.0, Unit.COUNT), idle.value("idle"));
        assertEquals(new Counter(1, Unit.COUNT), idle.value("borrows"));

        RecordedSample audit = released.written("audit");
        assertEquals(new Gauge(0, 4.0, Unit.COUNT), audit.value("active"));
        assertEquals(new Counter(0, Unit.COUNT), audit.value("leaks"), "leak detection on: a count");
        assertEquals(new Absent("no borrow yet"), audit.value("mean-borrow"), "no mean of nothing");
    }

    @Test
    void everyChartPlotsValuesTheSampleWrites() {
        boot("vidocq.pool.url", h2("default"), "vidocq.pool.audit.url", h2("audit"));
        RecordedSample sample = sample();

        for (String pool : sample.groupNames()) {
            RecordedSample group = sample.written(pool);
            for (Chart chart : panel.charts()) {
                for (Series series : chart.series()) {
                    RecordedSample.Value value = group.value(series.key());
                    if (series.style() == Series.Style.RATE) {
                        assertInstanceOf(Counter.class, value, chart.id() + " " + series);
                    } else {
                        Gauge gauge = assertInstanceOf(Gauge.class, value, chart.id() + " " + series);
                        if (series.style() == Series.Style.CEILING) {
                            assertNotNull(gauge.max(), chart.id() + ": a ceiling needs a max");
                        }
                    }
                }
            }
        }
    }

    @Test
    void onceStoppedThePanelShowsNoPool() {
        boot("vidocq.pool.url", h2("default"), "vidocq.pool.audit.url", h2("audit"));
        assertEquals(2, sample().groupNames().size());

        ext.onStop();

        assertEquals(List.of(), sample().groupNames(), "a poll during a dev reload reads no closed pool");
    }
}
