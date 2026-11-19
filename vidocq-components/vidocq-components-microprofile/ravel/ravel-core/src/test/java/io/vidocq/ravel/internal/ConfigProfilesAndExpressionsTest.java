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
import org.eclipse.microprofile.config.ConfigValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MP Config 3.1 §7.5 (profiles) + §7.2 (property expressions).
 */
@DisplayName("Config profiles + property expressions")
class ConfigProfilesAndExpressionsTest {

    @Test
    void profile_spec_section_7_5_overrides_base_key_with_same_source() {
        Config cfg = new RavelConfigBuilder()
                .withSources(MapConfigSource.of("s", 100, Map.of(
                        "mp.config.profile", "dev",
                        "app.url", "https://base",
                        "%dev.app.url", "https://dev"
                )))
                .build();

        assertEquals("https://dev", cfg.getValue("app.url", String.class));
    }

    @Test
    void profile_spec_section_7_5_keeps_base_value_when_profiled_key_missing() {
        Config cfg = new RavelConfigBuilder()
                .withSources(MapConfigSource.of("s", 100, Map.of(
                        "mp.config.profile", "dev",
                        "app.url", "https://base"
                )))
                .build();

        assertEquals("https://base", cfg.getValue("app.url", String.class));
    }

    @Test
    void profile_spec_section_7_5_multiple_profile_values_highest_ordinal_wins() {
        Config cfg = new RavelConfigBuilder()
                .withSources(
                        MapConfigSource.of("low", 100, Map.of("mp.config.profile", "dev")),
                        MapConfigSource.of("high", 400, Map.of("mp.config.profile", "prod")),
                        MapConfigSource.of("app", 200, Map.of(
                                "%dev.app.url", "https://dev",
                                "%prod.app.url", "https://prod",
                                "app.url", "https://base"
                        )))
                .build();

        assertEquals("https://prod", cfg.getValue("app.url", String.class));
    }

    @Test
    void expression_spec_section_7_2_resolves_simple_reference() {
        Config cfg = new RavelConfigBuilder()
                .withSources(MapConfigSource.of("s", 100, Map.of(
                        "host", "localhost",
                        "url", "http://${host}:8080"
                )))
                .build();

        assertEquals("http://localhost:8080", cfg.getValue("url", String.class));
    }

    @Test
    void expression_spec_section_7_2_resolves_default_when_missing() {
        Config cfg = new RavelConfigBuilder()
                .withSources(MapConfigSource.of("s", 100, Map.of(
                        "timeout", "${missing:30}"
                )))
                .build();

        assertEquals("30", cfg.getValue("timeout", String.class));
    }

    @Test
    void expression_spec_section_7_2_without_default_treats_property_as_missing() {
        // Spec §7.2 : si une expression ${key} n'a pas de valeur et pas de défaut,
        // la propriété est considérée absente — getValue lève NoSuchElementException
        // (pas IllegalArgumentException). Aligné sur TCK PropertyExpressionsTest.
        Config cfg = new RavelConfigBuilder()
                .withSources(MapConfigSource.of("s", 100, Map.of("timeout", "${missing}")))
                .build();

        assertThrows(java.util.NoSuchElementException.class, () -> cfg.getValue("timeout", String.class));
        assertEquals(java.util.Optional.empty(), cfg.getOptionalValue("timeout", String.class));
    }

    @Test
    void expression_spec_section_7_2_supports_nested_expressions() {
        Config cfg = new RavelConfigBuilder()
                .withSources(MapConfigSource.of("s", 100, Map.of(
                        "env", "dev",
                        "dev.url", "https://dev.example",
                        "url", "${${env}.url}"
                )))
                .build();

        assertEquals("https://dev.example", cfg.getValue("url", String.class));
    }

    @Test
    void expression_spec_section_7_2_supports_escape_dollar() {
        Config cfg = new RavelConfigBuilder()
                .withSources(MapConfigSource.of("s", 100, Map.of(
                        "name", "ravel",
                        "literal", "\\${name}"
                )))
                .build();

        assertEquals("${name}", cfg.getValue("literal", String.class));
    }

    @Test
    void expression_spec_section_7_2_cycle_direct_throws_IAE() {
        Config cfg = new RavelConfigBuilder()
                .withSources(MapConfigSource.of("s", 100, Map.of("a", "${a}")))
                .build();

        assertThrows(IllegalArgumentException.class, () -> cfg.getValue("a", String.class));
    }

    @Test
    void expression_spec_section_7_2_cycle_indirect_throws_IAE() {
        Config cfg = new RavelConfigBuilder()
                .withSources(MapConfigSource.of("s", 100, Map.of(
                        "a", "${b}",
                        "b", "${a}"
                )))
                .build();

        assertThrows(IllegalArgumentException.class, () -> cfg.getValue("a", String.class));
    }

    @Test
    void expression_spec_section_7_2_can_be_disabled_via_flag() {
        Config cfg = new RavelConfigBuilder()
                .withSources(MapConfigSource.of("s", 100, Map.of(
                        "mp.config.property.expressions.enabled", "false",
                        "name", "ravel",
                        "literal", "${name}"
                )))
                .build();

        assertEquals("${name}", cfg.getValue("literal", String.class));
    }

    @Test
    void expression_resolution_happens_before_conversion() {
        Config cfg = new RavelConfigBuilder()
                .withSources(MapConfigSource.of("s", 100, Map.of(
                        "raw.port", "8080",
                        "port", "${raw.port}"
                )))
                .build();

        assertEquals(8080, cfg.getValue("port", Integer.class));
    }

    @Test
    void profiles_and_expressions_interaction_resolves_expression_after_profile_selection() {
        Config cfg = new RavelConfigBuilder()
                .withSources(MapConfigSource.of("s", 100, Map.of(
                        "mp.config.profile", "dev",
                        "base", "https://example.org",
                        "%dev.url", "${base}/dev",
                        "url", "${base}/base"
                )))
                .build();

        assertEquals("https://example.org/dev", cfg.getValue("url", String.class));
    }

    @Test
    void getConfigValue_keeps_raw_expression_and_exposes_resolved_value() {
        Config cfg = new RavelConfigBuilder()
                .withSources(MapConfigSource.of("s", 100, Map.of(
                        "host", "localhost",
                        "url", "http://${host}:8080"
                )))
                .build();

        ConfigValue value = cfg.getConfigValue("url");
        assertNotNull(value);
        assertEquals("http://${host}:8080", value.getRawValue());
        assertEquals("http://localhost:8080", value.getValue());
    }
}


