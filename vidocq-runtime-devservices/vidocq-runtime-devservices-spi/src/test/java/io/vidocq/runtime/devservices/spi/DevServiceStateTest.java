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
package io.vidocq.runtime.devservices.spi;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DevServiceStateTest {

    @Test
    void minimalKeepsTheIdAndSortsTheKeys() {
        DevServiceState s = DevServiceState.minimal("x", List.of("b.url", "a.url"));
        assertEquals("x", s.id());
        assertNull(s.image());
        assertEquals(Map.of(), s.endpoints());
        assertEquals(List.of("a.url", "b.url"), s.injectedKeys());
    }

    @Test
    void aProviderThatDoesNotDescribeItselfGetsTheMinimalState() {
        DevService p = new DevService() {
            @Override
            public String id() {
                return "third-party";
            }

            @Override
            public boolean appliesWhen(DevServiceContext ctx) {
                return true;
            }

            @Override
            public Map<String, String> start(DevServiceContext ctx) {
                return Map.of();
            }

            @Override
            public void stop() {}
        };
        assertEquals(DevServiceState.minimal("third-party", List.of("k")), p.describe(Map.of("k", "v")));
    }

    @Test
    void theStateIsImmutable() {
        DevServiceState s = new DevServiceState(
                "x", "img", new LinkedHashMap<>(Map.of("a", "h:1")), new ArrayList<>(List.of("k")));
        assertThrows(UnsupportedOperationException.class, () -> s.endpoints().put("b", "h:2"));
        assertThrows(UnsupportedOperationException.class, () -> s.injectedKeys().add("z"));
    }
}
