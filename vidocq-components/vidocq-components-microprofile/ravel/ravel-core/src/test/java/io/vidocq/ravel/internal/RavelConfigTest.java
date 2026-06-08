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
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RavelConfig — cœur lookup, cascade ordinals (MP Config 3.1 §2.1)")
class RavelConfigTest {

    private static final Map<Class<?>, Converter<?>> STRING_ONLY =
            Map.of(String.class, IdentityStringConverter.INSTANCE);

    // --------- cascade & tie-breaking -----------

    @Test
    void higher_ordinal_source_wins() {
        // §3.4 — sources triées par ordinal décroissant ; la plus haute gagne.
        var low = MapConfigSource.of("low", 100, Map.of("k", "from-low"));
        var high = MapConfigSource.of("high", 400, Map.of("k", "from-high"));
        Config cfg = newConfig(List.of(low, high));

        assertEquals("from-high", cfg.getValue("k", String.class));
    }

    @Test
    void tie_breaking_first_registered_wins() {
        // Spec ne mandate pas le tie-breaking — choix Ravel : ordre d'enregistrement
        // du builder préservé en cas d'égalité d'ordinal.
        var first = MapConfigSource.of("first", 100, Map.of("k", "first"));
        var second = MapConfigSource.of("second", 100, Map.of("k", "second"));
        Config cfg = newConfig(List.of(first, second));

        assertEquals("first", cfg.getValue("k", String.class));
    }

    // --------- getValue / getOptionalValue / empty handling -----------

    @Test
    void getValue_missing_key_throws() {
        // §2.1 — getValue lève NoSuchElementException si clé absente.
        Config cfg = newConfig(List.of(MapConfigSource.of("s", 100, Map.of())));
        assertThrows(NoSuchElementException.class, () -> cfg.getValue("absent", String.class));
    }

    @Test
    void getOptionalValue_missing_key_returns_empty() {
        Config cfg = newConfig(List.of(MapConfigSource.of("s", 100, Map.of())));
        assertTrue(cfg.getOptionalValue("absent", String.class).isEmpty());
    }

    @Test
    void empty_string_is_treated_as_absent() {
        // §2.1.4 — "An empty string is considered as null."
        Config cfg = newConfig(List.of(MapConfigSource.of("s", 100, Map.of("k", ""))));
        assertTrue(cfg.getOptionalValue("k", String.class).isEmpty());
        assertThrows(NoSuchElementException.class, () -> cfg.getValue("k", String.class));
    }

    @Test
    void null_property_name_rejected() {
        Config cfg = newConfig(List.of());
        assertThrows(NullPointerException.class, () -> cfg.getValue(null, String.class));
        assertThrows(NullPointerException.class, () -> cfg.getOptionalValue(null, String.class));
    }

    @Test
    void unconvertible_type_throws() {
        // §5 — un type sans built-in et sans pattern §5.2 (of/valueOf/parse/(String))
        // doit lever IllegalArgumentException. {@link Object} n'a aucun de ces patterns.
        Config cfg = newConfig(List.of(MapConfigSource.of("s", 100, Map.of("k", "42"))));
        assertThrows(IllegalArgumentException.class, () -> cfg.getValue("k", Object.class));
    }

    // --------- getConfigValue (jamais null) -----------

    @Test
    void getConfigValue_returns_full_metadata_when_present() {
        // §2.1.5 — getConfigValue expose la source et l'ordinal.
        var src = MapConfigSource.of("MyProps", 250, Map.of("server.port", "8080"));
        Config cfg = newConfig(List.of(src));

        ConfigValue v = cfg.getConfigValue("server.port");
        assertNotNull(v);
        assertEquals("server.port", v.getName());
        assertEquals("8080", v.getValue());
        assertEquals("8080", v.getRawValue());
        assertEquals("MyProps", v.getSourceName());
        assertEquals(250, v.getSourceOrdinal());
    }

    @Test
    void getConfigValue_returns_non_null_for_missing_key() {
        // §2.1.5 — "the returned value will never be null".
        Config cfg = newConfig(List.of(MapConfigSource.of("s", 100, Map.of())));

        ConfigValue v = cfg.getConfigValue("absent");
        assertNotNull(v);
        assertEquals("absent", v.getName());
        assertNull(v.getValue());
        assertNull(v.getRawValue());
        assertNull(v.getSourceName());
        assertEquals(0, v.getSourceOrdinal());
    }

    // --------- getPropertyNames / getConfigSources -----------

    @Test
    void getPropertyNames_is_union_and_immutable() {
        var s1 = MapConfigSource.of("s1", 100, Map.of("a", "1", "b", "2"));
        var s2 = MapConfigSource.of("s2", 200, Map.of("b", "X", "c", "3"));
        Config cfg = newConfig(List.of(s1, s2));

        Set<String> names = new HashSet<>();
        cfg.getPropertyNames().forEach(names::add);
        assertEquals(Set.of("a", "b", "c"), names);

        // Iterable doit être immuable : cast vers Collection si possible et
        // tenter une mutation — sinon vérifier au minimum que chaque appel
        // retourne un Iterable cohérent.
        if (cfg.getPropertyNames() instanceof java.util.Collection<String> c) {
            assertThrows(UnsupportedOperationException.class, () -> c.add("zzz"));
        }
    }

    @Test
    void getConfigSources_is_sorted_by_ordinal_desc_and_immutable() {
        var low = MapConfigSource.of("low", 100, Map.of());
        var mid = MapConfigSource.of("mid", 250, Map.of());
        var high = MapConfigSource.of("high", 400, Map.of());
        Config cfg = newConfig(List.of(low, high, mid));

        var iter = cfg.getConfigSources().iterator();
        assertEquals("high", iter.next().getName());
        assertEquals("mid", iter.next().getName());
        assertEquals("low", iter.next().getName());
        assertFalse(iter.hasNext());

        if (cfg.getConfigSources() instanceof java.util.Collection<ConfigSource> c) {
            assertThrows(UnsupportedOperationException.class, () -> c.add(low));
        }
    }

    // --------- getConverter / unwrap -----------

    @Test
    void getConverter_returns_registered_converter_only() {
        Config cfg = newConfig(List.of());
        assertTrue(cfg.getConverter(String.class).isPresent());
        // Object n'a ni built-in ni pattern §5.2 → Optional.empty()
        assertTrue(cfg.getConverter(Object.class).isEmpty());
    }

    @Test
    void unwrap_returns_self_when_compatible() {
        RavelConfig cfg = newConfig(List.of());
        assertSame(cfg, cfg.unwrap(RavelConfig.class));
        assertSame(cfg, cfg.unwrap(Config.class));
    }

    @Test
    void unwrap_throws_for_incompatible_type() {
        Config cfg = newConfig(List.of());
        assertThrows(IllegalArgumentException.class, () -> cfg.unwrap(String.class));
    }

    @Test
    void implements_Config() {
        assertInstanceOf(Config.class, newConfig(List.of()));
    }

    // --------- helpers -----------

    private static RavelConfig newConfig(List<ConfigSource> sources) {
        return new RavelConfig(sources, STRING_ONLY, RavelConfigTest.class.getClassLoader());
    }
}
