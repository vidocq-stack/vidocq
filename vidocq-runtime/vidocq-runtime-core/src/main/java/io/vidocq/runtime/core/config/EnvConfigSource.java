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

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Source reading environment variables.
 * <p>
 * Three-pass resolution (MicroProfile Config rules):
 * <ol>
 *   <li>exact key</li>
 *   <li>replace non-alphanumeric characters with {@code _}</li>
 *   <li>same + upper-case</li>
 * </ol>
 */
public final class EnvConfigSource implements ConfigSource {

    @Override
    public String getName() {
        return "Environment";
    }

    @Override
    public int getOrdinal() {
        return 300;
    }

    @Override
    public String getValue(String key) {
        Map<String, String> env = System.getenv();
        String v = env.get(key);
        if (v != null) return v;
        String normalized = sanitize(key);
        v = env.get(normalized);
        if (v != null) return v;
        return env.get(normalized.toUpperCase(Locale.ROOT));
    }

    @Override
    public Set<String> getPropertyNames() {
        return System.getenv().keySet();
    }

    static String sanitize(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.toString();
    }
}
