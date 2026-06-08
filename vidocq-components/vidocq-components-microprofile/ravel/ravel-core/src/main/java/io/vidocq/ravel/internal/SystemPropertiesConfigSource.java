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
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * {@link ConfigSource} backed by {@link System#getProperties()} with default ordinal
 * 400 (MP Config 3.1 §3.4).
 *
 * <p>No local cache: system properties are mutable at runtime, so each call to
 * {@code getValue}/{@code getPropertyNames}/{@code getProperties} reads current state.</p>
 */
public final class SystemPropertiesConfigSource implements ConfigSource {

    private static final int ORDINAL = 400;
    private static final String NAME = "SystemPropertiesConfigSource";

    public SystemPropertiesConfigSource() {
        // ServiceLoader-friendly
    }

    @Override
    public String getValue(String propertyName) {
        return System.getProperty(propertyName);
    }

    @Override
    public Set<String> getPropertyNames() {
        return System.getProperties().stringPropertyNames();
    }

    @Override
    public Map<String, String> getProperties() {
        // Defensive snapshot: do not return mutable system Properties directly.
        Properties sys = System.getProperties();
        var copy = new HashMap<String, String>(sys.size());
        for (String name : sys.stringPropertyNames()) {
            String value = sys.getProperty(name);
            if (value != null) {
                copy.put(name, value);
            }
        }
        return Map.copyOf(copy);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrdinal() {
        // §3.4: system property {@code config_ordinal} overrides default ordinal.
        String override = System.getProperty("config_ordinal");
        if (override != null) {
            try {
                return Integer.parseInt(override.trim());
            } catch (NumberFormatException ignored) {
                // Non-parsable value: keep default ordinal.
            }
        }
        return ORDINAL;
    }
}
