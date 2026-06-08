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

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serial;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RavelConfigBuilder — fluent builder MP Config 3.1 §3")
class RavelConfigBuilderTest {

    @Test
    void implements_ConfigBuilder() {
        assertInstanceOf(ConfigBuilder.class, new RavelConfigBuilder());
    }

    // -------- empty builder --------

    @Test
    void build_without_sources_yields_empty_config() {
        // No source → all lookups fail; the String converter is still
        // present (built-in).
        Config cfg = new RavelConfigBuilder().build();
        assertTrue(cfg.getOptionalValue("anything", String.class).isEmpty());
        assertTrue(cfg.getConverter(String.class).isPresent());
    }

    // -------- addDefaultSources --------

    @Test
    void addDefaultSources_registers_three_canonical_sources() {
        // §3.4 — 3 sources built-in : SystemProps (400), EnvVars (300), MPProps (100).
        Config cfg = new RavelConfigBuilder().addDefaultSources().build();
        Set<String> names = new HashSet<>();
        for (ConfigSource s : cfg.getConfigSources()) {
            names.add(s.getName());
        }
        assertTrue(names.contains("SystemPropertiesConfigSource"));
        assertTrue(names.contains("EnvironmentVariablesConfigSource"));
        // MicroprofilePropertiesConfigSource[<url>] — prefixed
        boolean hasMpProps = names.stream().anyMatch(n -> n.startsWith("MicroprofilePropertiesConfigSource"));
        assertTrue(hasMpProps,
                "The fixture src/test/resources/META-INF/microprofile-config.properties must be detected");
    }

    // -------- withSources --------

    @Test
    void withSources_adds_custom_sources() {
        var custom = MapConfigSource.of("custom", 500, Map.of("x", "X"));
        Config cfg = new RavelConfigBuilder().withSources(custom).build();
        assertEquals("X", cfg.getValue("x", String.class));
    }

    @Test
    void withSources_combined_with_addDefaultSources() {
        var custom = MapConfigSource.of("custom", 500, Map.of("x", "X"));
        Config cfg = new RavelConfigBuilder()
                .addDefaultSources()
                .withSources(custom)
                .build();
        assertEquals("X", cfg.getValue("x", String.class));
        // Verify that the built-in sources are also present.
        boolean hasSysProps = false;
        for (ConfigSource s : cfg.getConfigSources()) {
            if ("SystemPropertiesConfigSource".equals(s.getName())) {
                hasSysProps = true;
            }
        }
        assertTrue(hasSysProps);
    }

    // -------- withConverter / withConverters --------

    @Test
    void withConverter_registers_typed_converter() {
        Converter<Integer> intConv = new IntConverter();
        Config cfg = new RavelConfigBuilder()
                .withConverter(Integer.class, 100, intConv)
                .withSources(MapConfigSource.of("s", 100, Map.of("k", "42")))
                .build();
        assertEquals(42, cfg.getValue("k", Integer.class));
    }

    @Test
    void withConverters_registers_multiple_converters() {
        Converter<Integer> intConv = new IntConverter();
        Converter<Boolean> boolConv = new BoolConverter();
        Config cfg = new RavelConfigBuilder()
                .withConverters(intConv, boolConv)
                .withSources(MapConfigSource.of("s", 100, Map.of("i", "7", "b", "true")))
                .build();
        assertEquals(7, cfg.getValue("i", Integer.class));
        assertEquals(Boolean.TRUE, cfg.getValue("b", Boolean.class));
    }

    @Test
    void without_converter_unsupported_type_throws() {
        // Object has neither a built-in converter (§5.1) nor the §5.2 pattern (of/valueOf/parse/(String))
        // → IllegalArgumentException expected.
        Config cfg = new RavelConfigBuilder()
                .withSources(MapConfigSource.of("s", 100, Map.of("k", "42")))
                .build();
        assertThrows(IllegalArgumentException.class, () -> cfg.getValue("k", Object.class));
    }

    // -------- forClassLoader --------

    @Test
    void forClassLoader_propagates_to_built_config() {
        ClassLoader cl = new java.net.URLClassLoader(new java.net.URL[0], null);
        RavelConfig cfg = (RavelConfig) new RavelConfigBuilder()
                .forClassLoader(cl)
                .build();
        assertSame(cl, cfg.getClassLoader());
    }

    // -------- addDiscoveredSources / addDiscoveredConverters --------

    @Test
    void addDiscoveredSources_is_safe_when_no_service_registered() {
        // No META-INF/services/...ConfigSource provided in ravel-core test
        // → addDiscoveredSources must add nothing and must not throw.
        Config cfg = new RavelConfigBuilder().addDiscoveredSources().build();
        assertFalse(cfg.getConfigSources().iterator().hasNext());
    }

    @Test
    void addDiscoveredConverters_is_safe_when_no_service_registered() {
        Config cfg = new RavelConfigBuilder().addDiscoveredConverters().build();
        assertTrue(cfg.getConverter(String.class).isPresent());
    }

    // -------- chaining --------

    @Test
    void all_methods_return_same_builder_for_chaining() {
        var b = new RavelConfigBuilder();
        assertSame(b, b.addDefaultSources());
        assertSame(b, b.addDiscoveredSources());
        assertSame(b, b.addDiscoveredConverters());
        assertSame(b, b.forClassLoader(getClass().getClassLoader()));
        assertSame(b, b.withSources(MapConfigSource.of("x", 10, Map.of())));
        assertSame(b, b.withConverters(new IntConverter()));
        assertSame(b, b.withConverter(Integer.class, 100, new IntConverter()));
    }

    // -------- helpers --------

    private static final class IntConverter implements Converter<Integer> {
        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public Integer convert(String value) {
            return Integer.parseInt(value);
        }
    }

    private static final class BoolConverter implements Converter<Boolean> {
        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public Boolean convert(String value) {
            return Boolean.parseBoolean(value);
        }
    }
}
