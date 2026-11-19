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

import java.util.Map;
import java.util.Set;

/**
 * Vidocq configuration source.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader} and sorted by
 * descending {@link #ordinal()} during resolution.</p>
 * <p>Ordinal conventions (MicroProfile Config aligned):</p>
 * <ul>
 *   <li>400 — system properties ({@code -Dkey=value})</li>
 *   <li>300 — environment variables</li>
 *   <li>100 — classpath {@code vidocq.properties} file</li>
 *   <li>0..99 — application defaults; custom overrides may also use values above 400</li>
 * </ul>
 */
public interface ConfigSource {

    /**
     * Source name, used for diagnostics.
     */
    String getName();

    /**
     * Source priority (higher wins).
     */
    int getOrdinal();

    /**
     * Raw value for a key, or {@code null} if unknown.
     */
    String getValue(String key);

    /**
     * Set of keys known by this source.
     */
    Set<String> getPropertyNames();

    /**
     * Read-only view of properties.
     *
     * <p>May be expensive for some sources.</p>
     */
    default Map<String, String> getProperties() {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        for (String name : getPropertyNames()) {
            String v = getValue(name);
            if (v != null) {
                map.put(name, v);
            }
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    /**
     * Alias for {@link #getOrdinal()}.
     */
    default int ordinal() {
        return getOrdinal();
    }
}
