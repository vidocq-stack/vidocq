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
package io.vidocq.heisenberg.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;

/**
 * Minimal mock of {@link Config} for {@link ConfigResolver} unit tests.
 * Supports only {@code getOptionalValue(String, Class)} and basic conversions
 * (String, Integer, Long, Boolean, Double).
 */
final class TestConfig implements Config {
    private final Map<String, String> values = new HashMap<>();

    TestConfig set(String key, String value) {
        values.put(key, value);
        return this;
    }

    @Override
    public <T> T getValue(String propertyName, Class<T> propertyType) {
        return getOptionalValue(propertyName, propertyType)
                .orElseThrow(() -> new NoSuchElementException(propertyName));
    }

    @Override
    public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
        String raw = values.get(propertyName);
        if (raw == null) return Optional.empty();
        return Optional.of(convert(raw, propertyType));
    }

    @Override
    public org.eclipse.microprofile.config.ConfigValue getConfigValue(String propertyName) {
        throw new UnsupportedOperationException("not used by ConfigResolver");
    }

    @Override public Iterable<String> getPropertyNames() { return new HashSet<>(values.keySet()); }
    @Override public Iterable<ConfigSource> getConfigSources() { return Set.of(); }
    @Override public <T> Optional<Converter<T>> getConverter(Class<T> forType) { return Optional.empty(); }
    @Override public <T> T unwrap(Class<T> type) { return null; }

    @SuppressWarnings("unchecked")
    private <T> T convert(String raw, Class<T> type) {
        if (type == String.class) return (T) raw;
        if (type == Integer.class) return (T) Integer.valueOf(raw);
        if (type == Long.class) return (T) Long.valueOf(raw);
        if (type == Boolean.class) return (T) Boolean.valueOf(raw);
        if (type == Double.class) return (T) Double.valueOf(raw);
        return (T) raw;
    }
}

