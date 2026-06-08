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
package io.vidocq.grimm.internal.config;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GrimmConfig} — spec §4.1 MicroProfile OpenAPI configuration keys.
 *
 * <p>The MicroProfile OpenAPI specification §4.1 enumerates the configuration keys
 * recognised by an implementation. This test exercises each of them through both
 * a flat {@link Map} source and a manual {@link Config} test double (no MP Config
 * implementation pulled in — keeping with the "zero impl dep" project rule).</p>
 */
class GrimmConfigTest {

    @Test
    void defaults_haveScanningEnabledAndNoOverrides() {
        GrimmConfig cfg = GrimmConfig.defaults();
        assertFalse(cfg.scan().disableScan());
        assertFalse(cfg.filter().hasFilter());
        assertFalse(cfg.filter().hasModelReader());
        assertTrue(cfg.servers().isEmpty());
        assertTrue(cfg.schemaOverrides().isEmpty());
        assertFalse(cfg.extensionsScanDisable());
    }

    @Test
    void fromMap_readsAllScanKeys() {
        // Spec §4.1 — five mp.openapi.scan.* keys.
        GrimmConfig cfg = GrimmConfig.fromMap(Map.of(
                "mp.openapi.scan.disable", "true",
                "mp.openapi.scan.packages", "com.acme.api, com.acme.rest",
                "mp.openapi.scan.classes", "com.acme.X,com.acme.Y",
                "mp.openapi.scan.exclude.packages", "com.acme.internal",
                "mp.openapi.scan.exclude.classes", "com.acme.Z"
        ));
        assertTrue(cfg.scan().disableScan());
        assertEquals(Set.of("com.acme.api", "com.acme.rest"), cfg.scan().includePackages());
        assertEquals(Set.of("com.acme.X", "com.acme.Y"), cfg.scan().includeClasses());
        assertEquals(Set.of("com.acme.internal"), cfg.scan().excludePackages());
        assertEquals(Set.of("com.acme.Z"), cfg.scan().excludeClasses());
    }

    @Test
    void fromMap_readsFilterAndModelReader() {
        // Spec §4.1 — mp.openapi.filter / mp.openapi.model.reader
        GrimmConfig cfg = GrimmConfig.fromMap(Map.of(
                "mp.openapi.filter", "com.acme.MyFilter",
                "mp.openapi.model.reader", "com.acme.MyReader"
        ));
        assertTrue(cfg.filter().hasFilter());
        assertEquals("com.acme.MyFilter", cfg.filter().filterClassName());
        assertTrue(cfg.filter().hasModelReader());
        assertEquals("com.acme.MyReader", cfg.filter().modelReaderClassName());
    }

    @Test
    void fromMap_readsServersAsCsvList() {
        // Spec §4.1 — mp.openapi.servers
        GrimmConfig cfg = GrimmConfig.fromMap(Map.of(
                "mp.openapi.servers", "https://prod.example.com, https://staging.example.com"
        ));
        assertEquals(2, cfg.servers().size());
        assertEquals("https://prod.example.com", cfg.servers().get(0));
        assertEquals("https://staging.example.com", cfg.servers().get(1));
    }

    @Test
    void fromMap_readsSchemaOverridesByPrefix() {
        // Spec §4.1 — mp.openapi.schema.<FQCN>
        GrimmConfig cfg = GrimmConfig.fromMap(Map.of(
                "mp.openapi.schema.com.acme.Pet", "{\"type\":\"string\"}",
                "mp.openapi.schema.com.acme.Owner", "{\"type\":\"object\"}"
        ));
        assertEquals(2, cfg.schemaOverrides().size());
        assertEquals("{\"type\":\"string\"}", cfg.schemaOverrides().get("com.acme.Pet"));
        assertEquals("{\"type\":\"object\"}", cfg.schemaOverrides().get("com.acme.Owner"));
    }

    @Test
    void fromMap_readsExtensionsScanDisable() {
        // Spec §4.1 — mp.openapi.extensions.scan.disable
        GrimmConfig cfg = GrimmConfig.fromMap(Map.of(
                "mp.openapi.extensions.scan.disable", "true"
        ));
        assertTrue(cfg.extensionsScanDisable());
    }

    @Test
    void fromMap_isImmutableSnapshot() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("mp.openapi.servers", "https://a");
        GrimmConfig cfg = GrimmConfig.fromMap(source);

        source.put("mp.openapi.servers", "https://changed");
        // Snapshot must not reflect the later mutation.
        assertEquals("https://a", cfg.servers().get(0));
        // List itself must be unmodifiable.
        assertThrows(UnsupportedOperationException.class, () -> cfg.servers().add("x"));
    }

    @Test
    void fromMpConfig_readsAllKeys() {
        // Same set of keys but exposed via a manual MP Config test double.
        Map<String, String> backing = new LinkedHashMap<>();
        backing.put("mp.openapi.scan.disable", "false");
        backing.put("mp.openapi.scan.packages", "com.acme");
        backing.put("mp.openapi.filter", "com.acme.MyFilter");
        backing.put("mp.openapi.servers", "https://prod");
        backing.put("mp.openapi.schema.com.acme.Pet", "{\"type\":\"string\"}");
        backing.put("mp.openapi.extensions.scan.disable", "true");

        GrimmConfig cfg = GrimmConfig.fromMpConfig(new FakeConfig(backing));
        assertFalse(cfg.scan().disableScan());
        assertEquals(Set.of("com.acme"), cfg.scan().includePackages());
        assertEquals("com.acme.MyFilter", cfg.filter().filterClassName());
        assertEquals("https://prod", cfg.servers().get(0));
        assertEquals("{\"type\":\"string\"}", cfg.schemaOverrides().get("com.acme.Pet"));
        assertTrue(cfg.extensionsScanDisable());
    }

    /** Manual Config test double — no MP Config implementation pulled in. */
    private static final class FakeConfig implements Config {
        private final Map<String, String> values;

        FakeConfig(Map<String, String> values) {
            this.values = values;
        }

        @Override public <T> T getValue(String name, Class<T> type) {
            String v = values.get(name);
            if (v == null) throw new java.util.NoSuchElementException(name);
            return type.cast(v);
        }
        @Override public ConfigValue getConfigValue(String name) {
            String v = values.get(name);
            return new ConfigValue() {
                @Override public String getName() { return name; }
                @Override public String getValue() { return v; }
                @Override public String getRawValue() { return v; }
                @Override public String getSourceName() { return "fake"; }
                @Override public int getSourceOrdinal() { return 0; }
            };
        }
        @Override public <T> Optional<T> getOptionalValue(String name, Class<T> type) {
            String v = values.get(name);
            return Optional.ofNullable(v).map(type::cast);
        }
        @Override public Iterable<String> getPropertyNames() {
            return new LinkedHashSet<>(values.keySet());
        }
        @Override public Iterable<ConfigSource> getConfigSources() { return Set.of(); }
        @Override public <T> Optional<org.eclipse.microprofile.config.spi.Converter<T>>
                getConverter(Class<T> forType) { return Optional.empty(); }
        @Override public <T> T unwrap(Class<T> type) { throw new IllegalArgumentException(); }
        // Java 25 default methods — implement getValues if needed (interface evolves)
        @SuppressWarnings("unused")
        public <T> java.util.List<T> getValues(String name, Class<T> itemType) {
            String v = values.get(name);
            if (v == null) return java.util.List.of();
            return java.util.Arrays.stream(v.split(","))
                    .map(String::trim).map(itemType::cast).toList();
        }
        @SuppressWarnings("unused")
        Collection<String> rawKeys() { return values.keySet(); }
    }
}

