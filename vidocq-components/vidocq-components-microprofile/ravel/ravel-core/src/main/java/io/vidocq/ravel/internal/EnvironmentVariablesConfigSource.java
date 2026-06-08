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

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * {@link ConfigSource} backed by {@link System#getenv()} with default ordinal 300
 * (MP Config 3.1 §3.4 / §7.6).
 *
 * <p>Spec §7.6 defines three key forms tried in order:</p>
 * <ol>
 *   <li>Exact match.</li>
 *   <li>Non-alphanumeric characters (except {@code _}) replaced by {@code _}.</li>
 *   <li>Step 2 converted to uppercase.</li>
 * </ol>
 */
public final class EnvironmentVariablesConfigSource implements ConfigSource {

    private static final int ORDINAL = 300;
    private static final String NAME = "EnvironmentVariablesConfigSource";

    public EnvironmentVariablesConfigSource() {
        // ServiceLoader-friendly
    }

    @Override
    public String getValue(String propertyName) {
        return lookup(propertyName, System::getenv);
    }

    @Override
    public Set<String> getPropertyNames() {
        // §7.6 — keys are returned as is (raw env var names).
        return System.getenv().keySet();
    }

    @Override
    public Map<String, String> getProperties() {
        return System.getenv();
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrdinal() {
        // §3.4: source-local {@code config_ordinal} overrides default ordinal.
        String override = System.getenv("config_ordinal");
        if (override != null) {
            try {
                return Integer.parseInt(override.trim());
            } catch (NumberFormatException ignored) {
                // Non-parsable value: keep default ordinal.
            }
        }
        return ORDINAL;
    }

    /**
     * Testable §7.6 mapping implementation using the {@link Function} lookup.
     * Package-private for unit tests without relying on real process environment.
     */
    static String lookup(String propertyName, Function<String, String> env) {
        // Form 1: exact
        String value = env.apply(propertyName);
        if (value != null) {
            return value;
        }
        // Form 2: non-alphanumeric -> _, original case preserved
        String envFormat = toEnvFormat(propertyName);
        if (!envFormat.equals(propertyName)) {
            value = env.apply(envFormat);
            if (value != null) {
                return value;
            }
        }
        // Form 3: form 2 + uppercase
        String upper = envFormat.toUpperCase(Locale.ROOT);
        if (!upper.equals(envFormat)) {
            value = env.apply(upper);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Replaces any non-alphanumeric character (except {@code _}) with {@code _}.
     * Package-private for testability.
     */
    static String toEnvFormat(String propertyName) {
        var sb = new StringBuilder(propertyName.length());
        for (int i = 0; i < propertyName.length(); i++) {
            char c = propertyName.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
