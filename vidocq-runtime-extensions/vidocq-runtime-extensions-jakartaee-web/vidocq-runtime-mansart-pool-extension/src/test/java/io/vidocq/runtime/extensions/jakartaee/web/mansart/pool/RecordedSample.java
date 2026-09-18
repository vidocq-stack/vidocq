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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;

import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Unit;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link PanelSample} that keeps what a panel writes on one poll, checked as the dev console checks it: every key
 * follows {@link PanelSample#requireKey}, a group is named and does not nest. The values are records, so that a test
 * compares them whole.
 */
final class RecordedSample implements PanelSample {

    /** One value of a scope. */
    sealed interface Value permits Gauge, Counter, Elapsed, Text, Absent, Table {}

    /** @param max the most it can reach, {@code null} for none */
    record Gauge(double value, Double max, Unit unit) implements Value {}

    record Counter(long total, Unit unit) implements Value {}

    record Elapsed(Duration value) implements Value {}

    record Text(String value) implements Value {}

    record Absent(String reason) implements Value {}

    record Table(List<String> columns, List<List<String>> rows) implements Value {}

    private final boolean group;
    private final Map<String, Value> values = new LinkedHashMap<>();
    private final Map<String, RecordedSample> groups = new LinkedHashMap<>();

    /** The sample of a panel, with its groups. */
    RecordedSample() {
        this(false);
    }

    private RecordedSample(boolean group) {
        this.group = group;
    }

    private PanelSample put(String key, Value value) {
        values.put(PanelSample.requireKey(key), value);
        return this;
    }

    @Override
    public PanelSample gauge(String key, double value, Unit unit) {
        return put(key, new Gauge(value, null, Objects.requireNonNull(unit, "unit")));
    }

    @Override
    public PanelSample gauge(String key, double value, double max, Unit unit) {
        return put(key, new Gauge(value, max, Objects.requireNonNull(unit, "unit")));
    }

    @Override
    public PanelSample counter(String key, long total, Unit unit) {
        return put(key, new Counter(total, Objects.requireNonNull(unit, "unit")));
    }

    @Override
    public PanelSample duration(String key, Duration value) {
        return put(key, value == null ? new Absent(null) : new Elapsed(value));
    }

    @Override
    public PanelSample text(String key, String value) {
        return put(key, value == null ? new Absent(null) : new Text(value));
    }

    @Override
    public PanelSample absent(String key, String reason) {
        return put(key, new Absent(reason));
    }

    @Override
    public PanelSample table(String key, List<String> columns, List<List<String>> rows) {
        return put(key, new Table(List.copyOf(columns), List.copyOf(rows)));
    }

    @Override
    public PanelSample group(String name) {
        if (group) {
            throw new IllegalStateException("groups do not nest");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a group has a name");
        }
        return groups.computeIfAbsent(name, n -> new RecordedSample(true));
    }

    /** The keys of this scope, in the order they were first written. */
    List<String> keys() {
        return List.copyOf(values.keySet());
    }

    /** The value {@code key} of this scope, or {@code null}. */
    Value value(String key) {
        return values.get(key);
    }

    /** The names of the groups, in the order of their first call. */
    List<String> groupNames() {
        return List.copyOf(groups.keySet());
    }

    /** The group {@code name}, which the panel wrote. */
    RecordedSample written(String name) {
        RecordedSample found = groups.get(name);
        if (found == null) {
            throw new AssertionError("no group " + name + " in " + groups.keySet());
        }
        return found;
    }
}
