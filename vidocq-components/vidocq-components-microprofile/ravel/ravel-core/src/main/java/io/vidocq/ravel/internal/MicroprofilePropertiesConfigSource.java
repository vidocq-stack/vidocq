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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * {@link ConfigSource} backed by a {@code META-INF/microprofile-config.properties}
 * file — default ordinal 100 (MP Config 3.1 §3.4).
 *
 * <p>One instance per URL found on the {@link ClassLoader}: a classpath containing
 * multiple JARs with this file produces as many distinct sources (see spec §3.4:
 * <em>"There can be multiple of these files, e.g. one per JAR."</em>).</p>
 *
 * <p>Snapshot frozen at load time — no hot-reload (outside MP Config 3.1 spec).</p>
 */
public final class MicroprofilePropertiesConfigSource implements ConfigSource {

    /** Canonical path of the MP Config properties file (§3.4). */
    public static final String RESOURCE_PATH = "META-INF/microprofile-config.properties";

    private static final int ORDINAL = 100;
    /** §7.5 — ordinal for profile-aware {@code microprofile-config-{profile}.properties} files. */
    private static final int PROFILED_ORDINAL = 110;

    private final String name;
    private final Map<String, String> data;
    private final int defaultOrdinal;

    private MicroprofilePropertiesConfigSource(String name, Map<String, String> data, int defaultOrdinal) {
        this.name = name;
        this.data = data;
        this.defaultOrdinal = defaultOrdinal;
    }

    /**
     * Enumerates all {@code META-INF/microprofile-config.properties} URLs accessible
     * from {@code classLoader} and produces one source per URL.
     *
     * @return immutable list, never {@code null}
     */
    public static List<MicroprofilePropertiesConfigSource> loadAll(ClassLoader classLoader) {
        return loadFromPath(classLoader, RESOURCE_PATH, ORDINAL);
    }

    /**
     * §7.5 — loads profile-aware {@code META-INF/microprofile-config-{profile}.properties}
     * files (default ordinal 110, overriding the non-profiled file).
     */
    public static List<MicroprofilePropertiesConfigSource> loadProfile(ClassLoader classLoader, String profile) {
        Objects.requireNonNull(profile, "profile");
        return loadFromPath(classLoader,
                "META-INF/microprofile-config-" + profile + ".properties", PROFILED_ORDINAL);
    }

    private static List<MicroprofilePropertiesConfigSource> loadFromPath(
            ClassLoader classLoader, String path, int defaultOrdinal) {
        Objects.requireNonNull(classLoader, "classLoader");
        var sources = new ArrayList<MicroprofilePropertiesConfigSource>();
        try {
            var urls = classLoader.getResources(path);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                sources.add(loadOne(url, defaultOrdinal));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to enumerate " + path + " from ClassLoader", e);
        }
        return List.copyOf(sources);
    }

    private static MicroprofilePropertiesConfigSource loadOne(URL url, int defaultOrdinal) {
        var props = new Properties();
        try (InputStream in = url.openStream()) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + url, e);
        }
        var data = new HashMap<String, String>(props.size());
        for (var name : props.stringPropertyNames()) {
            data.put(name, props.getProperty(name));
        }
        return new MicroprofilePropertiesConfigSource(
                "MicroprofilePropertiesConfigSource[" + url + "]",
                Collections.unmodifiableMap(data),
                defaultOrdinal);
    }

    @Override
    public String getValue(String propertyName) {
        return data.get(propertyName);
    }

    @Override
    public Set<String> getPropertyNames() {
        return data.keySet();
    }

    @Override
    public Map<String, String> getProperties() {
        return data;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getOrdinal() {
        // §3.4 — an optional {@code config_ordinal} entry in the file overrides
        // the default ordinal.
        String override = data.get("config_ordinal");
        if (override != null) {
            try {
                return Integer.parseInt(override.trim());
            } catch (NumberFormatException ignored) {
                // unparseable value → fall back to default ordinal
            }
        }
        return defaultOrdinal;
    }
}
