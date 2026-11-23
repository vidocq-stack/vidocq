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
package io.vidocq.runtime.extensions.microprofile.ravel;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Hands Vidocq's active profile, {@code vidocq.profile}, to MicroProfile Config as {@code mp.config.profile}, so that
 * Ravel applies the {@code %<profile>.} keys of every source under the profile {@code vidocq:dev} and the CLI set
 * (BUG-20261123-46).
 *
 * <p>Ravel discovers it as a MicroProfile {@code ConfigSource}. It holds the one key {@code mp.config.profile}, and
 * only when {@code vidocq.profile} has a value, read from the system properties, then the environment
 * ({@code VIDOCQ_PROFILE}), then {@code vidocq.properties} and {@code application.properties}. Its ordinal,
 * {@value #ORDINAL}, is above the system properties': the Vidocq profile wins over an {@code mp.config.profile} set
 * elsewhere, the rule {@code VidocqConfig} follows, so that both read the same keys. Without a Vidocq profile it holds
 * nothing and {@code mp.config.profile} keeps its MicroProfile meaning.</p>
 */
public final class VidocqProfileConfigSource implements org.eclipse.microprofile.config.spi.ConfigSource {

    /** Above the system properties (400); low enough for Ravel's profiled wrapper, at {@code ordinal + 1}. */
    static final int ORDINAL = 1000;

    private static final String PROFILE_KEY = "vidocq.profile";
    private static final String MP_PROFILE_KEY = "mp.config.profile";

    /** The Vidocq profile, {@code null} when there is none. */
    private final String profile;

    /** Reads {@code vidocq.profile} from the system properties, the environment, then the Vidocq files. */
    public VidocqProfileConfigSource() {
        this(lookup(System::getProperty, System::getenv, VidocqProfileConfigSource::fromFiles));
    }

    /**
     * @param lookup gives the value of a key, {@code null} when it has none
     */
    VidocqProfileConfigSource(UnaryOperator<String> lookup) {
        String value = lookup.apply(PROFILE_KEY);
        this.profile = value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * The value of a key from the system properties, else the environment under the MicroProfile names of the key
     * ({@code vidocq.profile}, {@code vidocq_profile}, {@code VIDOCQ_PROFILE}), else the files.
     */
    static UnaryOperator<String> lookup(UnaryOperator<String> system, UnaryOperator<String> environment,
                                        UnaryOperator<String> files) {
        return key -> {
            String value = system.apply(key);
            if (value != null) {
                return value;
            }
            String sanitized = key.replaceAll("[^A-Za-z0-9_]", "_");
            for (String name : new String[] {key, sanitized, sanitized.toUpperCase(Locale.ROOT)}) {
                value = environment.apply(name);
                if (value != null) {
                    return value;
                }
            }
            return files.apply(key);
        };
    }

    /** The value of a key in {@code vidocq.properties}, else in {@code application.properties}. */
    private static String fromFiles(String key) {
        String value = new VidocqPropertiesConfigSource().getValue(key);
        return value != null ? value : new ApplicationPropertiesConfigSource().getValue(key);
    }

    @Override
    public String getName() {
        return "VidocqProfileConfigSource";
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }

    @Override
    public String getValue(String key) {
        return MP_PROFILE_KEY.equals(key) ? profile : null;
    }

    @Override
    public Set<String> getPropertyNames() {
        return profile == null ? Set.of() : Set.of(MP_PROFILE_KEY);
    }

    @Override
    public Map<String, String> getProperties() {
        return profile == null ? Map.of() : Map.of(MP_PROFILE_KEY, profile);
    }
}
