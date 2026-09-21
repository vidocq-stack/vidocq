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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.runtime.spi.devconsole.Unit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ring the console fills whether or not a page is watching: what it keeps, what it refuses, and the hole an
 * absence leaves, which is the whole reason it exists.
 */
class PanelHistoryTest {

    private static final long T0 = 1_789_740_600_000L;

    private final PanelHistory history = new PanelHistory();

    /** The history of one panel, as the snapshot writes it. */
    private List<Map<String, Object>> written(String panel, long since) {
        JsonWriter out = new JsonWriter();
        history.writeTo(out, panel, since);
        return Json.array(out.toString());
    }

    /** Records one tick of {@code panel}, the sample built by {@code what}. */
    private void tick(long time, String panel, Consumer<RecordingSample> what) {
        RecordingSample sample = new RecordingSample();
        what.accept(sample);
        history.record(time, panel, sample);
    }

    @Test
    void aGaugeAndACounterAreKeptWithTheirUnitAndTheirCeiling() {
        tick(T0, "pool", s -> {
            s.gauge("active", 3, 8, Unit.COUNT);
            s.counter("borrows", 12, Unit.COUNT);
        });
        tick(T0 + 1000, "pool", s -> {
            s.gauge("active", 5, 8, Unit.COUNT);
            s.counter("borrows", 19, Unit.COUNT);
        });

        List<Map<String, Object>> series = written("pool", -1);

        assertEquals(2, series.size());
        Map<String, Object> active = series.get(0);
        assertEquals("active", active.get("key"));
        assertEquals("", active.get("group"), "the panel's own values are the group with no name");
        assertEquals("gauge", active.get("kind"));
        assertEquals("count", active.get("unit"));
        assertEquals(List.of(T0, T0 + 1000), active.get("t"));
        assertEquals(List.of(3L, 5L), active.get("v"), "a whole value is written whole: the document stays small");
        assertEquals(List.of(8L, 8L), active.get("max"));

        Map<String, Object> borrows = series.get(1);
        assertEquals("counter", borrows.get("kind"));
        assertEquals(List.of(12L, 19L), borrows.get("v"));
        assertFalse(borrows.containsKey("max"), "a counter has no ceiling, so no max array at all");
    }

    @Test
    void theValuesOfAGroupAreKeptUnderItsName() {
        tick(T0, "pool", s -> s.group("main").gauge("active", 3, Unit.COUNT));

        List<Map<String, Object>> series = written("pool", -1);

        assertEquals(1, series.size());
        assertEquals("main", series.get(0).get("group"));
        assertEquals("active", series.get(0).get("key"));
    }

    @Test
    void anAbsenceRecordsAHoleRatherThanSkippingThePoint() {
        tick(T0, "pool", s -> s.gauge("active", 3, Unit.COUNT));
        tick(T0 + 1000, "pool", s -> s.absent("active", "the pool is closed"));
        tick(T0 + 2000, "pool", s -> s.gauge("active", 4, Unit.COUNT));

        Map<String, Object> active = written("pool", -1).get(0);

        assertEquals(List.of(T0, T0 + 1000, T0 + 2000), active.get("t"),
                "the absent tick keeps its place on the axis");
        assertEquals(Arrays.asList(3L, null, 4L), active.get("v"),
                "a null, so the page cuts the curve; a zero would say the pool emptied");
    }

    @Test
    void anAbsenceOpensNoSeriesOfItsOwn() {
        tick(T0, "pool", s -> s.absent("never-measured", "nothing to read yet"));

        assertEquals(List.of(), written("pool", -1), "there is no kind to give it and nothing to draw");
        assertEquals(0, history.size());
    }

    @Test
    void whatIsNoMeasureIsNotKept() {
        tick(T0, "pool", s -> {
            s.text("url", "jdbc:h2:mem:demo");
            s.duration("borrow", java.time.Duration.ofMillis(18));
            s.table("rows", List.of("a"), List.of(List.of("1")));
            s.gauge("active", 3, Unit.COUNT);
        });

        List<Map<String, Object>> series = written("pool", -1);

        assertEquals(1, series.size(), "only the gauge: a text, a duration and a table have no curve");
        assertEquals("active", series.get(0).get("key"));
    }

    @Test
    void sinceSendsThePointsAfterItAndNoOthers() {
        for (int i = 0; i < 5; i++) {
            long at = T0 + i * 1000L;
            tick(at, "pool", s -> s.gauge("active", 1, Unit.COUNT));
        }

        assertEquals(List.of(T0, T0 + 1000, T0 + 2000, T0 + 3000, T0 + 4000), written("pool", -1).get(0).get("t"),
                "no since: the whole ring, which is what a tab that has just come back asks for");
        assertEquals(List.of(T0 + 3000, T0 + 4000), written("pool", T0 + 2000).get(0).get("t"));
        assertEquals(List.of(), written("pool", T0 + 4000).get(0).get("t"),
                "a page already up to date is sent the series and no point");
    }

    @Test
    void theRingKeepsTheLastThreeHundredPointsAndDropsTheOldest() {
        for (int i = 0; i < PanelHistory.MAX_POINTS + 50; i++) {
            long at = T0 + i * 1000L;
            double value = i;
            tick(at, "pool", s -> s.gauge("active", value, Unit.COUNT));
        }

        Map<String, Object> active = written("pool", -1).get(0);
        List<?> times = (List<?>) active.get("t");
        List<?> values = (List<?>) active.get("v");

        assertEquals(PanelHistory.MAX_POINTS, times.size());
        assertEquals(PanelHistory.MAX_POINTS, values.size());
        assertEquals(T0 + 50 * 1000L, times.get(0), "the fifty oldest ticks have rolled off");
        assertEquals(T0 + (PanelHistory.MAX_POINTS + 49) * 1000L, times.get(times.size() - 1));
        assertEquals(50L, values.get(0));
        assertEquals((long) (PanelHistory.MAX_POINTS + 49), values.get(values.size() - 1));
    }

    @Test
    void pastTheCapASeriesGetsNoCurveAndTheHistorySaysSo() {
        // One panel cannot hold 256 values in one scope: RecordingSample caps a scope at 64. Five panels do.
        for (int panel = 0; panel < 5; panel++) {
            String id = "panel-" + panel;
            tick(T0, id, s -> {
                for (int i = 0; i < RecordingSample.MAX_VALUES; i++) {
                    s.gauge("key-" + i, i, Unit.COUNT);
                }
            });
        }

        assertEquals(PanelHistory.MAX_SERIES, history.size(), "the cap is a cap, not a suggestion");
        assertTrue(history.truncated(), "and the page is told, rather than shown an empty chart with no reason");

        int kept = 0;
        for (int panel = 0; panel < 5; panel++) {
            kept += written("panel-" + panel, -1).size();
        }
        assertEquals(PanelHistory.MAX_SERIES, kept);
    }

    @Test
    void aSeriesRefusedForWantOfASlotNeverStartsLater() {
        for (int panel = 0; panel < 5; panel++) {
            String id = "panel-" + panel;
            tick(T0, id, s -> {
                for (int i = 0; i < RecordingSample.MAX_VALUES; i++) {
                    s.gauge("key-" + i, i, Unit.COUNT);
                }
            });
        }
        int before = history.size();

        tick(T0 + 1000, "latecomer", s -> s.gauge("active", 3, Unit.COUNT));

        assertEquals(before, history.size(), "first seen, first served: the ring is full");
        assertEquals(List.of(), written("latecomer", -1));
    }

    @Test
    void clearingForgetsEverySeriesAndItsRefusals() {
        tick(T0, "pool", s -> s.gauge("active", 3, Unit.COUNT));

        history.clear();

        assertEquals(0, history.size());
        assertFalse(history.truncated());
        assertEquals(List.of(), written("pool", -1));
    }

    @Test
    void aPanelThatHasNeverBeenSampledHasAnEmptyHistoryRatherThanNone() {
        assertEquals(List.of(), written("never-seen", -1));
    }

    @Test
    void theTicksOfTwoThreadsAreBothKept() throws InterruptedException {
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 2; t++) {
            int which = t;
            threads.add(Thread.ofPlatform().start(() -> {
                for (int i = 0; i < 100; i++) {
                    long at = T0 + i * 1000L;
                    tick(at, "panel-" + which, s -> s.gauge("active", 1, Unit.COUNT));
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(2, history.size());
        assertEquals(100, ((List<?>) written("panel-0", -1).get(0).get("t")).size());
        assertEquals(100, ((List<?>) written("panel-1", -1).get(0).get("t")).size());
    }

    @Test
    void aGaugeWithNoCeilingCarriesNoMaxArray() {
        tick(T0, "pool", s -> s.gauge("active", 3, Unit.COUNT));

        Map<String, Object> active = written("pool", -1).get(0);

        assertFalse(active.containsKey("max"));
        assertNull(active.get("max"));
    }
}
