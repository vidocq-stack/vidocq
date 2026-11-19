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
package io.vidocq.runtime.extensions.essentials.chappe;

import io.vidocq.runtime.spi.config.ConfigSource;
import io.vidocq.runtime.spi.config.VidocqConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Implementation of {@link VidocqConfig} test-only — avoid crossing a Java Modules dependency
 * to {@code vidocq-runtime-core} from the test module.
 */
final class TestConfig implements VidocqConfig {

    private final Map<String, String> data;
    private final List<ConfigSource> sources;

    private TestConfig(Map<String, String> data, List<ConfigSource> sources) {
        this.data = data;
        this.sources = sources;
    }

    /** One flat set of values, with no source behind them. */
    static TestConfig of(Map<String, String> data) {
        return new TestConfig(data, List.of());
    }

    /** Sources as the runtime layers them: each key takes its value from the source of highest ordinal that has it. */
    static TestConfig ofSources(ConfigSource... sources) {
        List<ConfigSource> sorted = Arrays.stream(sources)
                .sorted(Comparator.comparingInt(ConfigSource::getOrdinal).reversed())
                .toList();
        Map<String, String> merged = new HashMap<>();
        for (ConfigSource source : sorted) {
            source.getProperties().forEach(merged::putIfAbsent);
        }
        return new TestConfig(merged, sorted);
    }

    /** A source named {@code name}, of ordinal {@code ordinal}, holding {@code properties}. */
    static ConfigSource source(String name, int ordinal, Map<String, String> properties) {
        return new ConfigSource() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getOrdinal() {
                return ordinal;
            }

            @Override
            public String getValue(String key) {
                return properties.get(key);
            }

            @Override
            public Set<String> getPropertyNames() {
                return properties.keySet();
            }
        };
    }

    @Override
    public Optional<String> getValue(String key) {
        return Optional.ofNullable(data.get(key));
    }

    @Override
    public <T> Optional<T> getValue(String key, Class<T> type) {
        return getValue(key).map(raw -> convert(raw, type));
    }

    @Override
    public <T> List<T> getValues(String key, Class<T> elementType) {
        Optional<String> raw = getValue(key);
        if (raw.isEmpty()) return List.of();
        List<T> out = new ArrayList<>();
        for (String item : raw.get().split(",", -1)) {
            out.add(convert(item, elementType));
        }
        return out;
    }

    @Override
    public Iterable<String> getPropertyNames() {
        return data.keySet();
    }

    @Override
    public Iterable<ConfigSource> getConfigSources() {
        return sources;
    }

    @SuppressWarnings("unchecked")
    private static <T> T convert(String raw, Class<T> type) {
        if (type == String.class) return (T) raw;
        if (type == Integer.class) return (T) Integer.valueOf(raw);
        if (type == Boolean.class) return (T) Boolean.valueOf(raw);
        throw new IllegalArgumentException("Unsupported type in TestConfig: " + type);
    }
}
