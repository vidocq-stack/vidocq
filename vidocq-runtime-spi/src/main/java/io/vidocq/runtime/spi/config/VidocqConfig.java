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
package io.vidocq.runtime.spi.config;

import java.util.List;
import java.util.Optional;

/**
 * Access point to Vidocq configuration.
 *
 * <p>Properties are resolved by querying registered {@link ConfigSource} instances,
 * sorted by descending {@link ConfigSource#ordinal()} (first wins).</p>
 *
 * <p>The API is intentionally aligned with MicroProfile Config to ease future migration.</p>
 */
public interface VidocqConfig {

    /**
     * Retrieves the raw value of a property.
     */
    Optional<String> getValue(String key);

    /**
     * Retrieves a typed value.
     *
     * <p>A {@link Converter} must be registered for {@code type}.</p>
     */
    <T> Optional<T> getValue(String key, Class<T> type);

    /**
     * Retrieves a typed value with a default value.
     */
    default <T> T getValue(String key, Class<T> type, T defaultValue) {
        return getValue(key, type).orElse(defaultValue);
    }

    /**
     * Retrieves a list of typed values.
     *
     * <p>Values are comma-separated; use {@code \,} to escape a comma.</p>
     */
    <T> List<T> getValues(String key, Class<T> elementType);

    /**
     * All known keys across all sources.
     */
    Iterable<String> getPropertyNames();

    /**
     * Currently registered configuration sources, sorted by descending ordinal.
     */
    Iterable<ConfigSource> getConfigSources();
}
