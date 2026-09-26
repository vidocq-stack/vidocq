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

import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Unit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The {@link PanelSample} of one call of {@link io.vidocq.runtime.spi.devconsole.DevConsolePanel#sample}: it keeps
 * what the panel writes, checked and cleaned, then writes it into the snapshot.
 *
 * <p>Every key is checked with {@link PanelSample#requireKey}, so that a mistake fails the sample at once. Every
 * string is {@linkplain Texts#clean cleaned and cut}. A scope keeps {@value #MAX_VALUES} values and a sample
 * {@value #MAX_GROUPS} groups, a table {@value #MAX_ROWS} rows of {@value #MAX_COLUMNS} columns: what goes beyond is
 * dropped, and the sample says it was {@code truncated}. A value that is no measure, a gauge that is not a finite
 * number or a {@code null} text, is written absent, never as a zero.
 *
 * <p>One instance per call, on the thread of that call: nothing here is shared.
 */
final class RecordingSample implements PanelSample {

    static final int MAX_GROUPS = 64;
    static final int MAX_VALUES = 64;
    static final int MAX_ROWS = 100;
    static final int MAX_COLUMNS = 8;

    private final Scope values = new Scope(true);
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private boolean truncated;

    /** One value, as the snapshot writes it. */
    private sealed interface Value permits Gauge, Counter, Elapsed, Text, Absent, Table {}

    /** @param max the most it can reach, {@code NaN} for none */
    private record Gauge(double value, double max, Unit unit) implements Value {}

    private record Counter(long total, Unit unit) implements Value {}

    private record Elapsed(long nanos) implements Value {}

    private record Text(String value) implements Value {}

    /** @param reason why, or {@code null} */
    private record Absent(String reason) implements Value {}

    private record Table(List<String> columns, List<List<String>> rows) implements Value {}

    /** The values of the panel or of one of its groups, in the order their keys were first written. */
    private final class Scope {

        private final Map<String, Value> byKey = new LinkedHashMap<>();
        private final boolean kept;

        /** @param kept {@code false} for a group beyond the limit, which checks what it is given and keeps nothing */
        Scope(boolean kept) {
            this.kept = kept;
        }

        void put(String key, Value value) {
            PanelSample.requireKey(key);
            if (!kept) {
                return;
            }
            if (byKey.size() >= MAX_VALUES && !byKey.containsKey(key)) {
                truncated = true;
                return;
            }
            byKey.put(key, value);
        }

        void gauge(String key, double value, double max, Unit unit) {
            Objects.requireNonNull(unit, "unit");
            if (!Double.isFinite(value)) {
                put(key, new Absent(null));
            } else {
                put(key, new Gauge(value, Double.isFinite(max) && max > 0 ? max : Double.NaN, unit));
            }
        }

        void counter(String key, long total, Unit unit) {
            Objects.requireNonNull(unit, "unit");
            put(key, new Counter(total, unit));
        }

        void duration(String key, Duration value) {
            put(key, value == null ? new Absent(null) : new Elapsed(nanos(value)));
        }

        void text(String key, String value) {
            put(key, value == null ? new Absent(null) : new Text(Texts.clean(value)));
        }

        void absent(String key, String reason) {
            put(key, new Absent(Texts.clean(reason)));
        }

        void table(String key, List<String> columns, List<List<String>> rows) {
            Objects.requireNonNull(columns, "columns");
            Objects.requireNonNull(rows, "rows");
            if (columns.size() > MAX_COLUMNS || rows.size() > MAX_ROWS) {
                truncated = true;
            }
            List<String> heads = columns.stream().limit(MAX_COLUMNS).map(Texts::clean)
                    .map(cell -> cell == null ? "" : cell).toList();
            int replay = heads.indexOf(PanelSample.REPLAY_COLUMN);
            List<List<String>> table = new ArrayList<>();
            for (List<String> row : rows.subList(0, Math.min(MAX_ROWS, rows.size()))) {
                List<String> cells = new ArrayList<>(heads.size());
                for (int i = 0; i < heads.size(); i++) {
                    String raw = row != null && i < row.size() ? row.get(i) : null;
                    String cell = i == replay ? replayCell(raw) : Texts.clean(raw);
                    cells.add(cell == null ? "" : cell);
                }
                table.add(List.copyOf(cells));
            }
            put(key, new Table(heads, List.copyOf(table)));
        }

        void writeTo(JsonWriter out) {
            out.beginArray();
            byKey.forEach((key, value) -> write(out, key, value));
            out.endArray();
        }

        /** Reports the gauges, the counters and the absences of this scope, in the order their keys were written. */
        void forEachMeasure(String group, PanelHistory.MeasureSink sink) {
            byKey.forEach((key, value) -> {
                switch (value) {
                    case Gauge gauge ->
                            sink.measure(group, key, "gauge", unit(gauge.unit()), gauge.value(), gauge.max());
                    case Counter counter ->
                            sink.measure(group, key, "counter", unit(counter.unit()), counter.total(), Double.NaN);
                    case Absent ignored -> sink.measure(group, key, null, null, Double.NaN, Double.NaN);
                    default -> { }      // a duration, a text or a table: nothing a chart draws
                }
            });
        }
    }

    /** The scope of one group: the same values, and no group of its own. */
    private final class Group implements PanelSample {

        private final String name;
        private final Scope values;

        Group(String name, boolean kept) {
            this.name = name;
            this.values = new Scope(kept);
        }

        @Override
        public PanelSample gauge(String key, double value, Unit unit) {
            values.gauge(key, value, Double.NaN, unit);
            return this;
        }

        @Override
        public PanelSample gauge(String key, double value, double max, Unit unit) {
            values.gauge(key, value, max, unit);
            return this;
        }

        @Override
        public PanelSample counter(String key, long total, Unit unit) {
            values.counter(key, total, unit);
            return this;
        }

        @Override
        public PanelSample duration(String key, Duration value) {
            values.duration(key, value);
            return this;
        }

        @Override
        public PanelSample text(String key, String value) {
            values.text(key, value);
            return this;
        }

        @Override
        public PanelSample absent(String key, String reason) {
            values.absent(key, reason);
            return this;
        }

        @Override
        public PanelSample table(String key, List<String> columns, List<List<String>> rows) {
            values.table(key, columns, rows);
            return this;
        }

        @Override
        public PanelSample group(String name) {
            throw new IllegalStateException("group '" + this.name + "' cannot hold group '" + name
                    + "': groups do not nest");
        }
    }

    @Override
    public PanelSample gauge(String key, double value, Unit unit) {
        values.gauge(key, value, Double.NaN, unit);
        return this;
    }

    @Override
    public PanelSample gauge(String key, double value, double max, Unit unit) {
        values.gauge(key, value, max, unit);
        return this;
    }

    @Override
    public PanelSample counter(String key, long total, Unit unit) {
        values.counter(key, total, unit);
        return this;
    }

    @Override
    public PanelSample duration(String key, Duration value) {
        values.duration(key, value);
        return this;
    }

    @Override
    public PanelSample text(String key, String value) {
        values.text(key, value);
        return this;
    }

    @Override
    public PanelSample absent(String key, String reason) {
        values.absent(key, reason);
        return this;
    }

    @Override
    public PanelSample table(String key, List<String> columns, List<List<String>> rows) {
        values.table(key, columns, rows);
        return this;
    }

    @Override
    public PanelSample group(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a group needs a name, not \"" + name + "\"");
        }
        Group group = groups.get(name);
        if (group != null) {
            return group;
        }
        if (groups.size() >= MAX_GROUPS) {
            truncated = true;
            return new Group(name, false);
        }
        group = new Group(name, true);
        groups.put(name, group);
        return group;
    }

    /**
     * Reports every measure of this sample to {@code sink}: the gauges and the counters, which a chart draws, and
     * the absences, which break its curve. Durations, texts and tables have no curve and are not reported.
     *
     * <p>An absence is reported with a {@code null} kind and a {@code NaN} value: the history writes it as a hole,
     * and refuses to open a series on one, having no kind to give it.
     */
    void forEachMeasure(PanelHistory.MeasureSink sink) {
        values.forEachMeasure("", sink);
        for (Group group : groups.values()) {
            group.values.forEachMeasure(Texts.clean(group.name), sink);
        }
    }

    /**
     * Writes the members {@code truncated}, {@code values} and {@code groups} into the object {@code out} has open.
     */
    void writeTo(JsonWriter out) {
        out.name("truncated").value(truncated);
        out.name("values");
        values.writeTo(out);
        out.name("groups").beginArray();
        for (Group group : groups.values()) {
            out.beginObject().name("name").value(Texts.clean(group.name)).name("values");
            group.values.writeTo(out);
            out.endObject();
        }
        out.endArray();
    }

    private static void write(JsonWriter out, String key, Value value) {
        out.beginObject().name("key").value(key);
        switch (value) {
            case Gauge gauge -> {
                out.name("kind").value("gauge").name("value").value(gauge.value());
                if (!Double.isNaN(gauge.max())) {
                    out.name("max").value(gauge.max());
                }
                out.name("unit").value(unit(gauge.unit()));
            }
            case Counter counter -> out.name("kind").value("counter").name("value").value(counter.total())
                    .name("unit").value(unit(counter.unit()));
            case Elapsed elapsed -> out.name("kind").value("duration").name("nanos").value(elapsed.nanos());
            case Text text -> out.name("kind").value("text").name("value").value(text.value());
            case Absent absent -> {
                out.name("kind").value("absent");
                if (absent.reason() != null) {
                    out.name("reason").value(absent.reason());
                }
            }
            case Table table -> {
                out.name("kind").value("table").name("columns").beginArray();
                table.columns().forEach(out::value);
                out.endArray().name("rows").beginArray();
                for (List<String> row : table.rows()) {
                    out.beginArray();
                    row.forEach(out::value);
                    out.endArray();
                }
                out.endArray();
            }
        }
        out.endObject();
    }

    /**
     * A cell of the {@value PanelSample#REPLAY_COLUMN} column: kept whole up to
     * {@value PanelSample#MAX_REPLAY_CELL} characters, since a cut would break its JSON, and emptied past it.
     */
    private static String replayCell(String raw) {
        return raw == null || raw.length() > PanelSample.MAX_REPLAY_CELL
                ? null : Texts.clean(raw, PanelSample.MAX_REPLAY_CELL);
    }

    private static String unit(Unit unit) {
        return unit.name().toLowerCase(Locale.ROOT);
    }

    /** The nanoseconds of {@code duration}, held at the bounds of a {@code long} rather than overflowing. */
    private static long nanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException tooLong) {
            return duration.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }
}
