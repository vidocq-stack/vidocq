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

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The history of every measure the panels write, kept by the server rather than by the page.
 *
 * <h2>Why the server keeps it</h2>
 * <p>The page used to keep its own five minutes of points, one per poll. A browser stops firing a hidden tab's
 * timers, so the history stopped exactly when the tab went away — and the interval someone leaves the console to
 * cause is the interval they come back to look at. Here the tick runs for the life of the process, so the curve is
 * complete whether or not anyone is watching.
 *
 * <h2>What it costs</h2>
 * <p>{@value #MAX_SERIES} series of {@value #MAX_POINTS} points, and a point is a {@code long} and two
 * {@code double}s. Measured full, on a 64-bit JVM: <strong>1,966,960 bytes, 1.88 MB</strong> — and that is the
 * ceiling, not an average, since nothing here grows with the number of panels or the length of a run.
 * {@link RecordingSample} lets one panel write 64 groups of 64 values, so four busy panels reach the cap and it is
 * not decoration. Series are kept first seen, first served; one that arrives past the cap keeps its live value on
 * its tile and gets no curve, and {@link #truncated()} says so rather than letting the page draw an empty chart
 * with no explanation.
 *
 * <h2>What a point holds</h2>
 * <p>Gauges and counters only — what a chart draws. A panel that writes {@code absent} records a point whose value
 * is {@code NaN}, written {@code null}: the page cuts a curve on a null, so a value that goes away breaks its line
 * instead of being bridged across. Skipping the point would draw a straight line through the gap, which is the lie
 * this whole thing exists to remove. An {@code absent} for a key never seen as a measure records nothing: there is
 * no series yet, and no kind to give it.
 *
 * <p>A tick the server misses — a long pause, a suspended process — leaves no point at all, and the page cuts the
 * curve there too, because the gap to the next point exceeds what it expects. That hole is true, and stays.
 *
 * <p>Read from any number of request threads while the tick writes: the map is concurrent and each series is
 * guarded by itself, held for the few microseconds it takes to append or to copy a ring of 300.
 */
final class PanelHistory {

    /** The most series kept at once, across every panel. */
    static final int MAX_SERIES = 256;
    /** The most points kept per series: five minutes at one tick per second, what a chart shows. */
    static final int MAX_POINTS = 300;

    /** Joins a group and a key into one map key; a NUL can appear in neither, which the key rule checks. */
    private static final char SEPARATOR = '\0';

    /** By panel id, then by group and key within that panel. */
    private final Map<String, Map<String, Series>> byPanel = new ConcurrentHashMap<>();
    private final AtomicInteger series = new AtomicInteger();
    private final AtomicBoolean truncated = new AtomicBoolean();

    /** Whether a series was refused because {@value #MAX_SERIES} were already kept. */
    boolean truncated() {
        return truncated.get();
    }

    /** How many series are kept, for the tests and for the console's own figures. */
    int size() {
        return series.get();
    }

    /**
     * Records what one panel measured at {@code time}.
     *
     * @param time  the server's clock, in epoch milliseconds
     * @param panel the id of the panel
     * @param taken what it wrote this tick
     */
    void record(long time, String panel, RecordingSample taken) {
        Map<String, Series> keys = byPanel.computeIfAbsent(panel, id -> new ConcurrentHashMap<>());
        taken.forEachMeasure((group, key, kind, unit, value, max) -> {
            String at = group + SEPARATOR + key;
            Series existing = keys.get(at);
            if (existing == null) {
                // An absence is not enough to open a series: there is no kind to give it, and nothing to draw.
                if (kind == null || !room()) {
                    return;
                }
                existing = keys.computeIfAbsent(at, missing -> new Series(group, key, kind, unit));
            }
            existing.add(time, kind, unit, value, max);
        });
    }

    /** Takes one of the {@value #MAX_SERIES} slots, or refuses and remembers that it did. */
    private boolean room() {
        if (series.incrementAndGet() <= MAX_SERIES) {
            return true;
        }
        series.decrementAndGet();
        truncated.set(true);
        return false;
    }

    /**
     * Writes the history of {@code panel} as the array a snapshot carries, points after {@code since} only.
     *
     * @param out   the writer, with the panel's object open
     * @param panel the id of the panel
     * @param since the newest point the page already holds, negative for every point kept
     */
    void writeTo(JsonWriter out, String panel, long since) {
        Map<String, Series> keys = byPanel.get(panel);
        out.beginArray();
        if (keys != null) {
            // Sorted so that two snapshots of the same state read the same, whatever the map's iteration order.
            List<Series> ordered = keys.values().stream()
                    .sorted(Comparator.comparing(Series::group).thenComparing(Series::key))
                    .toList();
            for (Series each : ordered) {
                each.writeTo(out, since);
            }
        }
        out.endArray();
    }

    /** Forgets everything: a new boot starts its curves again. */
    void clear() {
        byPanel.clear();
        series.set(0);
        truncated.set(false);
    }

    /**
     * What {@link RecordingSample#forEachMeasure} reports: one gauge, counter or absence of one scope of one sample.
     */
    @FunctionalInterface
    interface MeasureSink {

        /**
         * @param group the group's name, {@code ""} for the panel's own values
         * @param key   the key, as the panel wrote it
         * @param kind  {@code "gauge"} or {@code "counter"}, {@code null} for an absence
         * @param unit  the unit, {@code null} for an absence
         * @param value the value, {@code NaN} for an absence
         * @param max   the ceiling of a gauge, {@code NaN} for none
         */
        void measure(String group, String key, String kind, String unit, double value, double max);
    }

    /**
     * One measured key, and the last {@value #MAX_POINTS} points of it, oldest first once read.
     *
     * <p>The ring is three arrays rather than a list of objects: 300 points per series and 256 series is 76,800
     * points, and each one would otherwise be an allocation the tick makes every second, for ever.
     */
    private static final class Series {

        private final String group;
        private final String key;
        private final long[] times = new long[MAX_POINTS];
        private final double[] values = new double[MAX_POINTS];
        private final double[] ceilings = new double[MAX_POINTS];
        private String kind;
        private String unit;
        /** Where the next point goes. */
        private int at;
        private int size;

        Series(String group, String key, String kind, String unit) {
            this.group = group;
            this.key = key;
            this.kind = kind;
            this.unit = unit;
        }

        String group() {
            return group;
        }

        String key() {
            return key;
        }

        synchronized void add(long time, String kind, String unit, double value, double max) {
            // A kind can change under a panel's feet — a gauge that becomes a counter. The newest wins: the page
            // reads one kind per series, and the points it draws are the recent ones.
            if (kind != null) {
                this.kind = kind;
                this.unit = unit;
            }
            times[at] = time;
            values[at] = value;
            ceilings[at] = max;
            at = (at + 1) % MAX_POINTS;
            if (size < MAX_POINTS) {
                size++;
            }
        }

        /** Writes this series, with the points after {@code since}; nothing at all when it has none left to send. */
        synchronized void writeTo(JsonWriter out, long since) {
            int first = at - size + MAX_POINTS;
            out.beginObject()
                    .name("group").value(group)
                    .name("key").value(key)
                    .name("kind").value(kind)
                    .name("unit").value(unit);
            boolean ceiling = false;
            for (int i = 0; i < size && !ceiling; i++) {
                ceiling = !Double.isNaN(ceilings[(first + i) % MAX_POINTS]);
            }
            out.name("t").beginArray();
            for (int i = 0; i < size; i++) {
                int p = (first + i) % MAX_POINTS;
                if (times[p] > since) {
                    out.value(times[p]);
                }
            }
            out.endArray().name("v").beginArray();
            for (int i = 0; i < size; i++) {
                int p = (first + i) % MAX_POINTS;
                if (times[p] <= since) {
                    continue;
                }
                if (Double.isNaN(values[p])) {
                    out.nullValue();        // absent: a hole in the curve, never a zero
                } else {
                    out.value(values[p]);
                }
            }
            out.endArray();
            if (ceiling) {
                out.name("max").beginArray();
                for (int i = 0; i < size; i++) {
                    int p = (first + i) % MAX_POINTS;
                    if (times[p] <= since) {
                        continue;
                    }
                    if (Double.isNaN(ceilings[p])) {
                        out.nullValue();
                    } else {
                        out.value(ceilings[p]);
                    }
                }
                out.endArray();
            }
            out.endObject();
        }
    }
}
