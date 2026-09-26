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
package io.vidocq.runtime.extensions.jakartaee.core.cassini.dev;

import io.vidocq.cassini.spi.http.CassiniStatistics;
import io.vidocq.runtime.extensions.jakartaee.core.cassini.live.RestMount;
import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.Series;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestPanelTest {

    /** Figures fixed by the test: what a stack would have counted. */
    record FixedStatistics(long requests, long inFlight, long[] byClass, long totalNanos, long maxNanos)
            implements CassiniStatistics {
        @Override
        public long responses(int statusClass) {
            return byClass[statusClass - 1];
        }
    }

    private static RestMount mount(String name, CassiniStatistics statistics) {
        return RestMount.of(name, "default", "", true, Set.of(), List.of(), statistics);
    }

    private static RecordingSample sample(List<RestMount> mounts) {
        RecordingSample sample = new RecordingSample();
        RestPanel.sample(mounts, sample);
        return sample;
    }

    @Test
    void writes_the_figures_of_each_mount_in_its_own_group() {
        var stats = new FixedStatistics(10, 2, new long[] {0, 7, 1, 1, 1}, 5_000_000, 3_000_000);

        RecordingSample group = sample(List.of(mount("vidocq.rest", stats))).groups().get("vidocq.rest");

        assertEquals("counter", group.kind("requests"));
        assertEquals(10, group.number("requests"));
        assertEquals("gauge", group.kind("in-flight"));
        assertEquals(2, group.number("in-flight"));
        assertEquals(0, group.number("status.1xx"));
        assertEquals(7, group.number("status.2xx"));
        assertEquals(1, group.number("status.3xx"));
        assertEquals(1, group.number("status.4xx"));
        assertEquals(1, group.number("status.5xx"));
        assertEquals("counter", group.kind("handler-time"));
        assertEquals(5_000_000, group.number("handler-time"));
        assertEquals(String.valueOf(Duration.ofNanos(500_000)), group.text("mean"));
        assertEquals(String.valueOf(Duration.ofNanos(3_000_000)), group.text("max"));
    }

    @Test
    void has_no_mean_before_the_first_request() {
        var stats = new FixedStatistics(0, 0, new long[5], 0, 0);

        RecordingSample group = sample(List.of(mount("vidocq.rest", stats))).groups().get("vidocq.rest");

        assertEquals(0, group.number("requests"), "a zero from a counter that exists is a measure");
        assertEquals("absent", group.kind("mean"));
        assertEquals("no request served yet", group.text("mean"));
        assertEquals("absent", group.kind("max"));
    }

    @Test
    void a_stack_without_counters_says_so_rather_than_writing_zeros() {
        RecordingSample group = sample(List.of(mount("vidocq.rest", null))).groups().get("vidocq.rest");

        assertEquals(Set.of("requests"), group.keys());
        assertEquals("absent", group.kind("requests"));
        assertEquals("counters off", group.text("requests"));
    }

    @Test
    void one_group_per_mount_in_mount_order() {
        var stats = new FixedStatistics(0, 0, new long[5], 0, 0);

        RecordingSample sample = sample(List.of(mount("vidocq.rest", stats), mount("health", stats)));

        assertEquals(List.of("vidocq.rest", "health"), List.copyOf(sample.groups().keySet()));
    }

    @Test
    void nothing_mounted_is_absent_not_zero() {
        RecordingSample sample = sample(List.of());

        assertTrue(sample.groups().isEmpty());
        assertEquals("absent", sample.kind("requests"));
        assertEquals("no REST resource mounted", sample.text("requests"));
    }

    @Test
    void every_chart_plots_keys_the_sample_writes() {
        var stats = new FixedStatistics(1, 0, new long[] {0, 1, 0, 0, 0}, 1, 1);
        Set<String> written = sample(List.of(mount("vidocq.rest", stats))).groups().get("vidocq.rest").keys();

        for (Chart chart : RestPanel.CHARTS) {
            for (Series series : chart.series()) {
                assertTrue(written.contains(series.key()), chart.id() + " plots " + series.key());
            }
        }
    }
}
