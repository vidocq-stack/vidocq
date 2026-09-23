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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.runtime.spi.config.ConfigSource;
import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.runtime.spi.report.LaunchMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The configuration of one boot as the {@code config} panel shows it, read once: strings only, the values already
 * {@linkplain ConfigValues#shown made safe to show}, so no secret is ever held here.
 *
 * <p><b>Which keys.</b> Those of the {@code vidocq.*} and {@code mp.*} namespaces, whatever source sets them, and
 * those the application's own sources define: every source but the system properties and the environment, told
 * apart by their names ({@code SystemProperties} and {@code Environment} for Vidocq's native sources,
 * {@code SystemPropertiesConfigSource} and {@code EnvironmentVariablesConfigSource} for Ravel's). A system property
 * or an environment variable is thus listed only when it overrides a key of the application, or is a Vidocq or
 * MicroProfile key: the hundreds of {@code java.*} properties and variables such as {@code PATH} are not.
 *
 * <p><b>Which source.</b> The first source, in lookup order, that has a value for the key: exactly what
 * {@link VidocqConfig#getValue(String)} returns. The environment answers a dotted key, {@code db.user}, for a
 * variable {@code DB_USER}; asking every source by key, not by its property names, finds it. The value is the raw
 * one of the source: under Ravel, before its profiles and its {@code ${...}} expressions, so an expression that names
 * a secret is shown as the expression, never expanded.
 *
 * <p><b>Order.</b> The application's keys first, then {@code vidocq.*}, then {@code mp.*}, each by name; the first
 * {@value #MAX_ROWS} only, the console's limit, the counts saying how many there were. A source that fails costs its
 * own keys and nothing else.
 *
 * @param sources       each source as {@code <name> <ordinal>}, in lookup order
 * @param rows          the first {@value #MAX_ROWS} keys: key, source, ordinal, value as shown
 * @param application   how many keys of the application's own sources were found
 * @param vidocq        how many {@code vidocq.*} keys
 * @param mp            how many {@code mp.*} keys
 * @param valuesShown   whether the values are shown: a dev launch only
 */
record ConfigInventory(List<String> sources, List<List<String>> rows, int application, int vidocq, int mp,
                       boolean valuesShown) {

    /** How many keys the table shows at most: the console's limit. */
    static final int MAX_ROWS = 100;

    private static final String VIDOCQ = "vidocq.";
    private static final String MP = "mp.";

    ConfigInventory {
        sources = List.copyOf(sources);
        rows = rows.stream().map(List::copyOf).toList();
    }

    /** How many keys were found, shown or not. */
    int keys() {
        return application + vidocq + mp;
    }

    /**
     * Reads {@code config} now.
     *
     * @param config the configuration of the boot
     * @param mode   the launch mode: the values are kept in {@link LaunchMode#DEV} only
     */
    static ConfigInventory read(VidocqConfig config, LaunchMode mode) {
        List<ConfigSource> lookup = new ArrayList<>();
        config.getConfigSources().forEach(lookup::add);
        List<String> sources = new ArrayList<>();
        Set<String> applicationKeys = new TreeSet<>();
        Set<String> vidocqKeys = new TreeSet<>();
        Set<String> mpKeys = new TreeSet<>();
        for (ConfigSource source : lookup) {
            sources.add(name(source) + " " + source.getOrdinal());
            boolean system = isSystemSource(name(source));
            Set<String> names;
            try {
                names = source.getPropertyNames();
            } catch (RuntimeException unreadable) {
                continue;
            }
            for (String key : names == null ? Set.<String>of() : names) {
                if (key == null) {
                    continue;
                }
                if (key.startsWith(VIDOCQ)) {
                    vidocqKeys.add(key);
                } else if (key.startsWith(MP)) {
                    mpKeys.add(key);
                } else if (!system) {
                    applicationKeys.add(key);
                }
            }
        }
        List<List<String>> rows = new ArrayList<>();
        int application = rows(applicationKeys, lookup, mode, rows);
        int vidocq = rows(vidocqKeys, lookup, mode, rows);
        int mp = rows(mpKeys, lookup, mode, rows);
        return new ConfigInventory(sources, rows, application, vidocq, mp, mode == LaunchMode.DEV);
    }

    /**
     * Whether a source is the system properties or the environment, rather than one of the application's own.
     *
     * @param name the source's name
     */
    static boolean isSystemSource(String name) {
        return name.contains("SystemProperties") || name.contains("Environment");
    }

    /**
     * Adds a row per key that some source has a value for, while the table has room; returns how many keys had one.
     */
    private static int rows(Set<String> keys, List<ConfigSource> lookup, LaunchMode mode, List<List<String>> rows) {
        int found = 0;
        for (String key : keys) {
            for (ConfigSource source : lookup) {
                String value;
                try {
                    value = source.getValue(key);
                } catch (RuntimeException unreadable) {
                    continue;
                }
                if (value != null) {
                    found++;
                    if (rows.size() < MAX_ROWS) {
                        rows.add(List.of(key, name(source), String.valueOf(source.getOrdinal()),
                                String.valueOf(ConfigValues.shown(key, value, mode))));
                    }
                    break;
                }
            }
        }
        return found;
    }

    private static String name(ConfigSource source) {
        String name = source.getName();
        return name == null ? "unnamed" : name;
    }
}
