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
package io.vidocq.humboldt.otel.interop;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapConfigPropertiesTest {

    private static final String MISSING = "missing";
    private static final String EMPTY = "empty";

    private final MapConfigProperties config = new MapConfigProperties(Map.of(
            "s", "text",
            "b", "true",
            "i", "42",
            "l", "9000000000",
            "d", "2.5",
            "dur", "PT3S",
            "list", "a,b,c",
            EMPTY, "",
            "map", "k1=v1, k2 = v2,noequals,=novalue"));

    @Test
    void typed_getters_parse_the_values() {
        assertEquals("text", config.getString("s"));
        assertEquals(Boolean.TRUE, config.getBoolean("b"));
        assertEquals(42, config.getInt("i"));
        assertEquals(9_000_000_000L, config.getLong("l"));
        assertEquals(2.5, config.getDouble("d"));
        assertEquals(Duration.ofSeconds(3), config.getDuration("dur"));
    }

    @Test
    void typed_getters_return_null_for_missing_keys() {
        assertNull(config.getString(MISSING));
        assertNull(config.getBoolean(MISSING));
        assertNull(config.getInt(MISSING));
        assertNull(config.getLong(MISSING));
        assertNull(config.getDouble(MISSING));
        assertNull(config.getDuration(MISSING));
    }

    @Test
    void list_is_split_on_commas_and_empty_when_absent() {
        assertEquals(List.of("a", "b", "c"), config.getList("list"));
        assertTrue(config.getList(EMPTY).isEmpty());
        assertTrue(config.getList(MISSING).isEmpty());
    }

    @Test
    void map_keeps_only_key_value_pairs() {
        assertEquals(Map.of("k1", "v1", "k2", "v2"), config.getMap("map"));
        assertTrue(config.getMap(EMPTY).isEmpty());
        assertTrue(config.getMap(MISSING).isEmpty());
    }

    @Test
    void default_constructor_has_a_component_loader() {
        assertNotNull(new MapConfigProperties(Map.of()).getComponentLoader());
    }
}
