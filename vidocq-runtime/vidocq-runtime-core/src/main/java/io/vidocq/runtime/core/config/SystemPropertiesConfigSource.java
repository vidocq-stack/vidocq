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
package io.vidocq.runtime.core.config;

import io.vidocq.runtime.spi.config.ConfigSource;

import java.util.HashSet;
import java.util.Set;

/**
 * Source reading {@link System#getProperties() system properties}.
 * Ordinal 400 (MicroProfile Config default).
 */
public final class SystemPropertiesConfigSource implements ConfigSource {

    @Override
    public String getName() {
        return "SystemProperties";
    }

    @Override
    public int getOrdinal() {
        return 400;
    }

    @Override
    public String getValue(String key) {
        return System.getProperty(key);
    }

    @Override
    public Set<String> getPropertyNames() {
        Set<String> names = new HashSet<>();
        for (Object k : System.getProperties().keySet()) {
            names.add(String.valueOf(k));
        }
        return names;
    }
}
