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

import io.vidocq.runtime.spi.config.ConfigSource;
import io.vidocq.runtime.spi.config.ConfigSourceProvider;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that {@link RavelConfigSourceProvider} exposes the
 * {@code org.eclipse.microprofile.config.spi.ConfigSource} (including sources
 * Custom MPs recorded by the user) in {@link ConfigSource} Vidocq.
 *
 * <p>Unit granularity test: we directly instantiate the provider and we
 * inspects its sources, without going through {@code VidocqConfigImpl}. It is
 * sufficient to ensure that on the extension side, delegation to MP Config is
 * faithful; faithful; aggregation side {@code VidocqConfigImpl} via ServiceLoader is
 * validated by core module tests.</p>
 */
@DisplayName("RavelConfigSourceProvider - delegation to MP Config")
class RavelConfigSourceProviderTest {

    @AfterEach
    void cleanup() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        resolver.releaseConfig(resolver.getConfig(getClass().getClassLoader()));
    }

    @Test
    @DisplayName("getConfigSources exposes a custom user MP source")
    void exposesCustomMpSourceAsVidocqSource() {
        registerMpSource("custom", 1000, Map.of("vidocq.test.x", "y"));

        ConfigSourceProvider provider = new RavelConfigSourceProvider();
        boolean foundCustom = false;
        for (ConfigSource src : provider.getConfigSources(getClass().getClassLoader())) {
            if ("custom".equals(src.getName())) {
                assertEquals(1000, src.getOrdinal());
                assertEquals("y", src.getValue("vidocq.test.x"));
                foundCustom = true;
                break;
            }
        }
        assertTrue(foundCustom, "The custom MP source must be exposed via the provider");
    }

    @Test
    @DisplayName("getConfigSources includes MP built-ins (Sys, Env, MP-properties)")
    void exposesBuiltinMpSources() {
        // No custom recording — Ravel must already have its default sources.
        ConfigSourceProvider provider = new RavelConfigSourceProvider();

        Set<String> names = new HashSet<>();
        for (ConfigSource src : provider.getConfigSources(getClass().getClassLoader())) {
            names.add(src.getName());
        }
        // The exact names come from Ravel; we at least check the presence
        // a source of type "system" and an "environment".
        boolean hasSys = names.stream().anyMatch(n -> n.toLowerCase().contains("system"));
        boolean hasEnv = names.stream().anyMatch(n -> n.toLowerCase().contains("env"));
        assertTrue(hasSys, "Expected an MP built-in System source, got " + names);
        assertTrue(hasEnv, "Expected an MP built-in Environment source, got " + names);
    }

    // ---- helpers ----

    private void registerMpSource(String name, int ordinal, Map<String, String> values) {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        var config = resolver.getBuilder()
                .addDefaultSources()
                .withSources(new MapMpConfigSource(name, ordinal, values))
                .forClassLoader(getClass().getClassLoader())
                .build();
        resolver.registerConfig(config, getClass().getClassLoader());
    }

    private static final class MapMpConfigSource
            implements org.eclipse.microprofile.config.spi.ConfigSource {
        private final String name;
        private final int ordinal;
        private final Map<String, String> values;

        MapMpConfigSource(String name, int ordinal, Map<String, String> values) {
            this.name = name;
            this.ordinal = ordinal;
            this.values = Map.copyOf(values);
        }

        @Override public Map<String, String> getProperties() { return values; }
        @Override public Set<String> getPropertyNames() { return values.keySet(); }
        @Override public String getValue(String key) { return values.get(key); }
        @Override public String getName() { return name; }
        @Override public int getOrdinal() { return ordinal; }
    }
}
