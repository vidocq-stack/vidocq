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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Unit;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a panel wrote during one {@link io.vidocq.runtime.spi.devconsole.LivePanel#sample sample}, checked as the
 * console checks it: every key follows {@link PanelSample#requireKey}, and the same key written twice keeps the
 * last value. Copied from the runtime module's own test fixture (Vidocq/vidocq#143), unchanged apart from the
 * {@code @link} above (the panel contract moved to {@code LivePanel}).
 */
final class RecordingSample implements PanelSample {

    /** One value: its kind, then its number, its text, or the reason it is absent. */
    record Value(String kind, double number, String text) {}

    private final Map<String, Value> values = new LinkedHashMap<>();

    /** The keys written, in the order they were written. */
    Set<String> keys() {
        return values.keySet();
    }

    /** Whether the panel wrote nothing at all. */
    boolean isEmpty() {
        return values.isEmpty();
    }

    /** The kind of a key: {@code gauge}, {@code counter}, {@code duration}, {@code text}, {@code absent}, {@code table}. */
    String kind(String key) {
        Value value = values.get(key);
        return value == null ? null : value.kind();
    }

    /** The number of a gauge or a counter; {@link Double#NaN} when the key holds something else. */
    double number(String key) {
        Value value = values.get(key);
        return value == null ? Double.NaN : value.number();
    }

    /** The reason an absent key has none, or the text of a text value. */
    String text(String key) {
        Value value = values.get(key);
        return value == null ? null : value.text();
    }

    @Override
    public PanelSample gauge(String key, double value, Unit unit) {
        return put(key, new Value("gauge", value, null));
    }

    @Override
    public PanelSample gauge(String key, double value, double max, Unit unit) {
        return put(key, new Value("gauge", value, null));
    }

    @Override
    public PanelSample counter(String key, long total, Unit unit) {
        return put(key, new Value("counter", total, null));
    }

    @Override
    public PanelSample duration(String key, Duration value) {
        return put(key, new Value("duration", Double.NaN, String.valueOf(value)));
    }

    @Override
    public PanelSample text(String key, String value) {
        return put(key, new Value("text", Double.NaN, value));
    }

    @Override
    public PanelSample absent(String key, String reason) {
        return put(key, new Value("absent", Double.NaN, reason));
    }

    @Override
    public PanelSample table(String key, List<String> columns, List<List<String>> rows) {
        return put(key, new Value("table", Double.NaN, String.valueOf(rows)));
    }

    @Override
    public PanelSample group(String name) {
        throw new UnsupportedOperationException("the mcp panel writes one scope, not groups: " + name);
    }

    private PanelSample put(String key, Value value) {
        values.put(PanelSample.requireKey(key), value);
        return this;
    }
}
