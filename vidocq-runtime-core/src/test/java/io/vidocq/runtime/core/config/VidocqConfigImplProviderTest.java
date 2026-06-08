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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates replacement semantics of {@code ConfigSourceProvider}: when
 * a provider is registered, its sources completely replace the sources
 * native ServiceLoader-discoveries. Unit test that injects a list
 * explicit via the constructor {@code (List)} to avoid the effects of
 * classpath of ServiceLoader.
 *
 * <p>The “real provider via ServiceLoader” scenario is validated in E2E by
 * {@code RavelConfigSourceProviderTest} in the Ravel extension.</p>
 */
@DisplayName("VidocqConfigImpl - replacement semantics vs native")
class VidocqConfigImplProviderTest {

    @Test
    @DisplayName("Explicit list replaces all ServiceLoader discovery")
    void explicitListBypassesServiceLoader() {
        ConfigSource s = source("mock", 999, Map.of("vidocq.test.key", "from-mock"));
        VidocqConfig cfg = new VidocqConfigImpl(List.of(s));
        assertEquals("from-mock", cfg.getValue("vidocq.test.key").orElseThrow());

        // A possible native source (SystemPropertiesConfigSource ordinal 400)
        // has not been loaded: only the explicit source is used.
        boolean foundOnlyMock = false;
        int count = 0;
        for (ConfigSource src : cfg.getConfigSources()) {
            count++;
            if ("mock".equals(src.getName())) foundOnlyMock = true;
        }
        assertEquals(1, count, "The explicit list replaces any other source");
        assertTrue(foundOnlyMock);
    }

    @Test
    @DisplayName("Explicit empty list -> no sources (does not fall back to native)")
    void emptyExplicitListIsEmpty() {
        VidocqConfig cfg = new VidocqConfigImpl(List.of());
        assertFalse(cfg.getValue("vidocq.any").isPresent());
        int count = 0;
        for (ConfigSource ignored : cfg.getConfigSources()) count++;
        assertEquals(0, count);
    }

    @Test
    @DisplayName("Explicit sources sorted by descending ordinal")
    void sortsByDescendingOrdinal() {
        ConfigSource low = source("low", 50, Map.of("k", "low"));
        ConfigSource high = source("high", 500, Map.of("k", "high"));
        ConfigSource mid = source("mid", 200, Map.of("k", "mid"));
        VidocqConfig cfg = new VidocqConfigImpl(List.of(low, high, mid));

        assertEquals("high", cfg.getValue("k").orElseThrow());

        java.util.List<String> names = new java.util.ArrayList<>();
        cfg.getConfigSources().forEach(s -> names.add(s.getName()));
        assertEquals(List.of("high", "mid", "low"), names);
    }

    // ---- helpers ----

    private static ConfigSource source(String name, int ordinal, Map<String, String> data) {
        return new ConfigSource() {
            @Override public String getName() { return name; }
            @Override public int getOrdinal() { return ordinal; }
            @Override public String getValue(String key) { return data.get(key); }
            @Override public Set<String> getPropertyNames() { return data.keySet(); }
        };
    }
}
