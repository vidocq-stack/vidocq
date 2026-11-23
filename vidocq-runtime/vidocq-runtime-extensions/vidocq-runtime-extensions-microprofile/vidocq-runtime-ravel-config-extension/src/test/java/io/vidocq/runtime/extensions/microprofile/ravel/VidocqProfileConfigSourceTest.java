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

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-20261123-46: {@code vidocq:dev} sets {@code vidocq.profile=dev}, and Ravel honours {@code %dev.} keys only
 * under {@code mp.config.profile}. {@link VidocqProfileConfigSource} hands Vidocq's profile to Ravel.
 */
class VidocqProfileConfigSourceTest {

    @Test
    void namesTheVidocqProfileAsTheMicroProfileOne() {
        VidocqProfileConfigSource source = new VidocqProfileConfigSource(key ->
                "vidocq.profile".equals(key) ? "dev" : null);
        assertEquals("dev", source.getValue("mp.config.profile"));
        assertEquals(Set.of("mp.config.profile"), source.getPropertyNames());
        assertNull(source.getValue("vidocq.profile"));
    }

    @Test
    void namesNothingWithoutAVidocqProfile() {
        VidocqProfileConfigSource source = new VidocqProfileConfigSource(_ -> null);
        assertNull(source.getValue("mp.config.profile"));
        assertTrue(source.getPropertyNames().isEmpty());

        VidocqProfileConfigSource blank = new VidocqProfileConfigSource(_ -> "  ");
        assertNull(blank.getValue("mp.config.profile"));
    }

    @Test
    void readsTheProfileFromTheSystemPropertiesThenTheEnvironmentThenTheFiles() {
        Map<String, String> sys = Map.of();
        Map<String, String> env = Map.of("VIDOCQ_PROFILE", "staging");
        assertEquals("staging", VidocqProfileConfigSource.lookup(sys::get, env::get, _ -> "file").apply("vidocq.profile"));
        assertEquals("sys", VidocqProfileConfigSource.lookup(_ -> "sys", env::get, _ -> "file").apply("vidocq.profile"));
        assertEquals("file", VidocqProfileConfigSource.lookup(sys::get, Map.<String, String>of()::get, _ -> "file")
                .apply("vidocq.profile"));
    }

    @Test
    void makesRavelApplyTheProfiledKeysOfTheVidocqProfile() {
        Config config = ConfigProviderResolver.instance().getBuilder()
                .withSources(new VidocqProfileConfigSource(_ -> "dev"),
                        new MapSource("microprofile-config.properties", 100,
                                Map.of("greeting", "hello", "%dev.greeting", "hello from dev")))
                .build();
        assertEquals("hello from dev", config.getValue("greeting", String.class));
    }

    @Test
    void theVidocqProfileWinsOverAMicroProfileProfileSetElsewhere() {
        Config config = ConfigProviderResolver.instance().getBuilder()
                .withSources(new VidocqProfileConfigSource(_ -> "dev"),
                        new MapSource("system", 400, Map.of("mp.config.profile", "prod")),
                        new MapSource("file", 100, Map.of("k", "plain", "%dev.k", "dev", "%prod.k", "prod")))
                .build();
        assertEquals("dev", config.getValue("k", String.class));
    }

    @Test
    void ravelDiscoversIt() throws IOException {
        // On the class path, from the services file; this module has no `uses` to ask a ServiceLoader with.
        String services;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("META-INF/services/" + ConfigSource.class.getName())) {
            services = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(services.lines().anyMatch(VidocqProfileConfigSource.class.getName()::equals), services);

        // On the module path, from the module descriptor.
        ModuleDescriptor descriptor = VidocqProfileConfigSource.class.getModule().getDescriptor();
        if (descriptor != null) {
            assertTrue(descriptor.provides().stream()
                    .filter(p -> p.service().equals(ConfigSource.class.getName()))
                    .anyMatch(p -> p.providers().contains(VidocqProfileConfigSource.class.getName())),
                    descriptor::toString);
        }
    }

    private record MapSource(String name, int ordinal, Map<String, String> values) implements ConfigSource {
        @Override public Map<String, String> getProperties() { return values; }
        @Override public Set<String> getPropertyNames() { return values.keySet(); }
        @Override public String getValue(String key) { return values.get(key); }
        @Override public String getName() { return name; }
        @Override public int getOrdinal() { return ordinal; }
    }
}
