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
package io.vidocq.runtime.spi.devconsole;

import io.vidocq.runtime.spi.devconsole.Series.Style;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link Chart} and {@link Series}: what a panel asks the page to draw, checked when it is built. */
class ChartTest {

    @Test
    void aSeriesNamesAValueAndHowToDrawIt() {
        Series active = new Series("active", Style.AREA);

        assertEquals("active", active.key());
        assertEquals(Style.AREA, active.style());
    }

    @Test
    void eachStyleHasAFactory() {
        assertEquals(new Series("active", Style.AREA), Series.area("active"));
        assertEquals(new Series("idle", Style.STACKED), Series.stacked("idle"));
        assertEquals(new Series("waiting", Style.LINE), Series.line("waiting"));
        assertEquals(new Series("borrows", Style.RATE), Series.rate("borrows"));
        assertEquals(new Series("active", Style.CEILING), Series.ceiling("active"));
    }

    @Test
    void aSeriesNamesAValidKeyAndHasAStyle() {
        assertThrows(IllegalArgumentException.class, () -> new Series("Heap used", Style.LINE));
        assertThrows(NullPointerException.class, () -> new Series(null, Style.LINE));
        assertThrows(NullPointerException.class, () -> new Series("heap.used", null));
    }

    @Test
    void aChartKeepsItsOwnCopyOfItsSeries() {
        List<Series> series = new ArrayList<>(List.of(Series.area("active"), Series.stacked("idle")));
        Chart chart = new Chart("connections", "Connections", series);

        series.clear();

        assertEquals(List.of(Series.area("active"), Series.stacked("idle")), chart.series());
        List<Series> chartSeries = chart.series();
        Series waiting = Series.line("waiting");
        assertThrows(UnsupportedOperationException.class, () -> chartSeries.add(waiting));
    }

    @Test
    void theIdFollowsTheRuleOfValueKeys() {
        assertEquals("gc", new Chart("gc", "GC", List.of(Series.rate("time"))).id());
        List<Series> active = List.of(Series.area("active"));
        assertThrows(IllegalArgumentException.class, () -> new Chart("Connections", "Connections", active));
        assertThrows(NullPointerException.class, () -> new Chart(null, "Connections", active));
    }

    @Test
    void aChartHasATitle() {
        List<Series> active = List.of(Series.area("active"));
        assertThrows(NullPointerException.class, () -> new Chart("connections", null, active));
        assertThrows(IllegalArgumentException.class, () -> new Chart("connections", " \t", active));
    }

    @Test
    void aChartDrawsAtLeastOneSeriesAndNoNullOne() {
        assertThrows(NullPointerException.class, () -> new Chart("connections", "Connections", null));
        List<Series> none = List.of();
        assertThrows(IllegalArgumentException.class, () -> new Chart("connections", "Connections", none));
        List<Series> withNull = new ArrayList<>();
        withNull.add(Series.area("active"));
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new Chart("connections", "Connections", withNull));
    }

    @Test
    void aStackedSeriesStacksOnAnAreaOrAStackedSeriesBeforeIt() {
        new Chart("memory", "Memory", List.of(Series.area("eden"), Series.stacked("survivor"),
                Series.stacked("old")));
        new Chart("memory", "Memory", List.of(Series.area("eden"), Series.line("committed"),
                Series.stacked("old")));

        List<Series> stackedAlone = List.of(Series.stacked("old"));
        List<Series> stackedOnALine = List.of(Series.line("committed"), Series.stacked("old"), Series.area("eden"));
        assertThrows(IllegalArgumentException.class, () -> new Chart("memory", "Memory", stackedAlone));
        assertThrows(IllegalArgumentException.class, () -> new Chart("memory", "Memory", stackedOnALine));
    }

    @Test
    void aValueMayBeDrawnInTwoStylesButNotTwiceInTheSameOne() {
        Chart connections = new Chart("connections", "Connections", List.of(Series.area("active"),
                Series.stacked("idle"), Series.line("waiting"), Series.ceiling("active")));
        assertEquals(4, connections.series().size());

        List<Series> twice = List.of(Series.rate("borrows"), Series.rate("borrows"));
        assertThrows(IllegalArgumentException.class, () -> new Chart("throughput", "Throughput", twice));
    }
}
