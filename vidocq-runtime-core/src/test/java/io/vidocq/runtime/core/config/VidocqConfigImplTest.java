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
package io.vidocq.runtime.core.config;

import io.vidocq.runtime.spi.config.ConfigSource;
import io.vidocq.runtime.spi.config.VidocqConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VidocqConfigImplTest {

    @Test
    void returnsEmptyWhenNoSourceKnowsKey() {
        VidocqConfig cfg = new VidocqConfigImpl(List.of(source("A", 100, Map.of())));
        assertEquals(Optional.empty(), cfg.getValue("missing"));
    }

    @Test
    void higherOrdinalWins() {
        ConfigSource low = source("low", 100, Map.of("k", "low"));
        ConfigSource high = source("high", 500, Map.of("k", "high"));
        VidocqConfig cfg = new VidocqConfigImpl(List.of(low, high));
        assertEquals("high", cfg.getValue("k").orElseThrow());
    }

    @Test
    void fallsThroughToNextSourceWhenMissing() {
        ConfigSource low = source("low", 100, Map.of("k", "fallback"));
        ConfigSource high = source("high", 500, Map.of("other", "x"));
        VidocqConfig cfg = new VidocqConfigImpl(List.of(low, high));
        assertEquals("fallback", cfg.getValue("k").orElseThrow());
    }

    @Test
    void convertsBuiltinTypes() {
        ConfigSource s = source("s", 100, Map.of(
                "port", "8080",
                "enabled", "true",
                "timeout", "PT30S",
                "ratio", "0.5",
                "uri", "http://example.com",
                "path", "/tmp/foo"
        ));
        VidocqConfig cfg = new VidocqConfigImpl(List.of(s));
        assertEquals(8080, cfg.getValue("port", Integer.class).orElseThrow());
        assertEquals(Boolean.TRUE, cfg.getValue("enabled", Boolean.class).orElseThrow());
        assertEquals(Duration.ofSeconds(30), cfg.getValue("timeout", Duration.class).orElseThrow());
        assertEquals(0.5d, cfg.getValue("ratio", Double.class).orElseThrow());
        assertEquals(URI.create("http://example.com"), cfg.getValue("uri", URI.class).orElseThrow());
        assertEquals(Path.of("/tmp/foo"), cfg.getValue("path", Path.class).orElseThrow());
    }

    @Test
    void defaultValueWhenMissing() {
        VidocqConfig cfg = new VidocqConfigImpl(List.of());
        assertEquals(42, cfg.getValue("absent", Integer.class, 42));
    }

    @Test
    void parsesListSplittingOnComma() {
        ConfigSource s = source("s", 100, Map.of("items", "a,b,c"));
        VidocqConfig cfg = new VidocqConfigImpl(List.of(s));
        assertEquals(List.of("a", "b", "c"), cfg.getValues("items", String.class));
    }

    @Test
    void parsesListWithEscapedComma() {
        ConfigSource s = source("s", 100, Map.of("items", "a\\,b,c"));
        VidocqConfig cfg = new VidocqConfigImpl(List.of(s));
        assertEquals(List.of("a,b", "c"), cfg.getValues("items", String.class));
    }

    @Test
    void emptyListWhenKeyMissing() {
        VidocqConfig cfg = new VidocqConfigImpl(List.of());
        assertTrue(cfg.getValues("absent", Integer.class).isEmpty());
    }

    @Test
    void unionOfPropertyNames() {
        ConfigSource a = source("a", 100, Map.of("x", "1", "y", "2"));
        ConfigSource b = source("b", 200, Map.of("y", "3", "z", "4"));
        VidocqConfig cfg = new VidocqConfigImpl(List.of(a, b));
        List<String> names = new ArrayList<>();
        cfg.getPropertyNames().forEach(names::add);
        assertEquals(Set.of("x", "y", "z"), new java.util.HashSet<>(names));
    }

    @Test
    void sourcesExposedInDescendingOrdinal() {
        ConfigSource a = source("low", 10, Map.of());
        ConfigSource b = source("high", 100, Map.of());
        ConfigSource c = source("mid", 50, Map.of());
        VidocqConfig cfg = new VidocqConfigImpl(List.of(a, b, c));
        List<String> names = new ArrayList<>();
        cfg.getConfigSources().forEach(s -> names.add(s.getName()));
        assertEquals(List.of("high", "mid", "low"), names);
    }

    @Test
    void throwsWhenConverterUnknown() {
        ConfigSource s = source("s", 100, Map.of("k", "v"));
        VidocqConfig cfg = new VidocqConfigImpl(List.of(s));
        assertThrows(IllegalArgumentException.class,
                () -> cfg.getValue("k", java.math.BigDecimal.class));
    }

    private static ConfigSource source(String name, int ordinal, Map<String, String> data) {
        return new ConfigSource() {
            @Override public String getName() { return name; }
            @Override public int getOrdinal() { return ordinal; }
            @Override public String getValue(String key) { return data.get(key); }
            @Override public Set<String> getPropertyNames() { return data.keySet(); }
        };
    }
}
