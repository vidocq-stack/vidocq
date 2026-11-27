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
        assertEquals(true, cfg.getValue("enabled", Boolean.class).orElseThrow());
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

    @Test
    void expandsAPropertyExpressionBeforeConverting() {
        // BUG-20261114-01: the port of the default listener written as an expression in vidocq.properties
        ConfigSource file = source("vidocq.properties", 100,
                Map.of("vidocq.chappe.listener.default.port", "${DASHBOARD_PORT:8080}"));
        assertEquals(8080, new VidocqConfigImpl(List.of(file))
                .getValue("vidocq.chappe.listener.default.port", Integer.class).orElseThrow());

        ConfigSource env = source("env", 300, Map.of("DASHBOARD_PORT", "9090"));
        assertEquals(9090, new VidocqConfigImpl(List.of(file, env))
                .getValue("vidocq.chappe.listener.default.port", Integer.class).orElseThrow());
    }

    @Test
    void anExpressionWhoseKeyHasNoValueLeavesTheKeyWithoutValue() {
        VidocqConfig cfg = new VidocqConfigImpl(List.of(source("s", 100, Map.of("k", "${missing}"))));
        assertEquals(Optional.empty(), cfg.getValue("k"));
        assertEquals(42, cfg.getValue("k", Integer.class, 42));
    }

    @Test
    void expandsAListBeforeSplittingIt() {
        ConfigSource s = source("s", 100, Map.of("items", "${base},c", "base", "a,b"));
        VidocqConfig cfg = new VidocqConfigImpl(List.of(s));
        assertEquals(List.of("a", "b", "c"), cfg.getValues("items", String.class));
    }

    @Test
    void theMicroProfileKeyTurnsTheExpansionOff() {
        ConfigSource s = source("s", 100, Map.of("k", "${other}", "other", "x",
                "mp.config.property.expressions.enabled", "false"));
        assertEquals("${other}", new VidocqConfigImpl(List.of(s)).getValue("k").orElseThrow());
    }

    // ---------- configuration profiles (BUG-20261123-46, MicroProfile Config 3.1 "Config Profile") ----------

    @Test
    void theVidocqProfileMakesAProfiledKeyOverrideThePlainOne() {
        ConfigSource sys = source("sys", 400, Map.of("vidocq.profile", "dev"));
        ConfigSource file = source("vidocq.properties", 100,
                Map.of("vidocq.http.port", "8080", "%dev.vidocq.http.port", "8081"));
        VidocqConfig cfg = new VidocqConfigImpl(List.of(sys, file));
        assertEquals(8081, cfg.getValue("vidocq.http.port", Integer.class).orElseThrow());
    }

    @Test
    void aProfiledKeyIsIgnoredWithoutAProfileOrUnderAnotherOne() {
        Map<String, String> data = Map.of("vidocq.http.port", "8080", "%dev.vidocq.http.port", "8081");
        assertEquals("8080", new VidocqConfigImpl(List.of(source("file", 100, data)))
                .getValue("vidocq.http.port").orElseThrow());

        ConfigSource prod = source("sys", 400, Map.of("vidocq.profile", "prod"));
        assertEquals("8080", new VidocqConfigImpl(List.of(prod, source("file", 100, data)))
                .getValue("vidocq.http.port").orElseThrow());
    }

    @Test
    void theMicroProfileProfileKeyActivatesAProfileWhenVidocqNamesNone() {
        ConfigSource file = source("file", 100,
                Map.of("mp.config.profile", "dev", "k", "plain", "%dev.k", "dev"));
        assertEquals("dev", new VidocqConfigImpl(List.of(file)).getValue("k").orElseThrow());
    }

    @Test
    void theVidocqProfileWinsOverTheMicroProfileOne() {
        ConfigSource file = source("file", 100, Map.of("mp.config.profile", "prod", "vidocq.profile", "dev",
                "k", "plain", "%dev.k", "dev", "%prod.k", "prod"));
        assertEquals("dev", new VidocqConfigImpl(List.of(file)).getValue("k").orElseThrow());
    }

    @Test
    void aPlainKeyOfAHigherOrdinalSourceStillWinsOverAProfiledKeyOfALowerOne() {
        ConfigSource sys = source("sys", 400, Map.of("vidocq.profile", "dev", "k", "sys"));
        ConfigSource file = source("file", 100, Map.of("%dev.k", "dev"));
        ConfigSource env = source("env", 300, Map.of("%dev.other", "env-dev"));
        ConfigSource low = source("low", 50, Map.of("other", "low"));
        VidocqConfig cfg = new VidocqConfigImpl(List.of(sys, file, env, low));
        assertEquals("sys", cfg.getValue("k").orElseThrow());
        assertEquals("env-dev", cfg.getValue("other").orElseThrow());
    }

    @Test
    void anExpressionResolvesTheProfiledValueOfTheKeyItNames() {
        ConfigSource file = source("file", 100, Map.of("vidocq.profile", "dev",
                "url", "http://${host}:8080", "host", "example.com", "%dev.host", "localhost"));
        assertEquals("http://localhost:8080", new VidocqConfigImpl(List.of(file)).getValue("url").orElseThrow());
    }

    @Test
    void theActiveProfileIsReadAsTheSourcesHoldIt() {
        ConfigSource file = source("file", 100, Map.of("vidocq.profile", " dev ", "%dev.vidocq.profile", "other"));
        assertEquals(Optional.of("dev"), new VidocqConfigImpl(List.of(file)).activeProfile());
        assertEquals(Optional.empty(), new VidocqConfigImpl(List.of(source("empty", 100, Map.of()))).activeProfile());
    }

    @Test
    void theActiveProfileExposesItsKeysUnderTheirPlainNames() {
        ConfigSource file = source("file", 100,
                Map.of("vidocq.profile", "dev", "%dev.only.in.dev", "x", "%prod.only.in.prod", "y"));
        List<String> names = new ArrayList<>();
        new VidocqConfigImpl(List.of(file)).getPropertyNames().forEach(names::add);
        assertTrue(names.contains("only.in.dev"), names::toString);
        assertFalse(names.contains("only.in.prod"), names::toString);
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
