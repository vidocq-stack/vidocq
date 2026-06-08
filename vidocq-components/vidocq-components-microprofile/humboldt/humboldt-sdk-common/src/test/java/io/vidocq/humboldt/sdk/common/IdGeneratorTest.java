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
package io.vidocq.humboldt.sdk.common;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdGeneratorTest {

    private final IdGenerator gen = IdGenerator.random128();

    @Test
    void traceId_is_32_hex_chars() {
        for (int i = 0; i < 100; i++) {
            String id = gen.generateTraceId();
            assertEquals(32, id.length(), "traceId must be 32 characters: " + id);
            assertTrue(id.matches("[0-9a-f]{32}"), "traceId must be lowercase hex: " + id);
            assertFalse(id.equals("0".repeat(32)), "traceId must not be all-zero");
        }
    }

    @Test
    void spanId_is_16_hex_chars() {
        for (int i = 0; i < 100; i++) {
            String id = gen.generateSpanId();
            assertEquals(16, id.length(), "spanId must be 16 characters: " + id);
            assertTrue(id.matches("[0-9a-f]{16}"), "spanId must be lowercase hex: " + id);
            assertFalse(id.equals("0".repeat(16)), "spanId must not be all-zero");
        }
    }

    @Test
    void traceId_is_unique_over_10k_iterations() {
        Set<String> seen = new HashSet<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            assertTrue(seen.add(gen.generateTraceId()), "traceId collision at iteration " + i);
        }
    }

    @Test
    void spanId_is_unique_over_10k_iterations() {
        Set<String> seen = new HashSet<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            assertTrue(seen.add(gen.generateSpanId()), "spanId collision at iteration " + i);
        }
    }
}
