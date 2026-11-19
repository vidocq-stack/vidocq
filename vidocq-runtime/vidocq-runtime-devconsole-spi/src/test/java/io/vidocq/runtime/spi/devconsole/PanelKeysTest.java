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
package io.vidocq.runtime.spi.devconsole;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rule every value key, series key and chart id follows: what a panel may name a value. */
class PanelKeysTest {

    private static final String FORTY = "a234567890123456789012345678901234567890";

    @ParameterizedTest
    @ValueSource(strings = {"a", "active", "heap.used", "mean-borrow", "threads.daemon", "cpu.process", "p99",
            "a.b-c.d", "a-", "a.", FORTY})
    void aKeyStartsWithALowercaseLetterFollowedByUpToThirtyNineLowercaseLettersDigitsDotsOrHyphens(String key) {
        assertEquals(key, PanelSample.requireKey(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "Heap", "heapUsed", "heap used", " heap", "heap ", "1heap", ".heap", "-heap",
            "heap_used", "heap/used", "heap:used", "héap", "heap\n", FORTY + "1"})
    void anyOtherKeyIsRefused(String key) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PanelSample.requireKey(key));
        assertTrue(refused.getMessage().contains("[a-z][a-z0-9.-]{0,39}"),
                "the message says the rule: " + refused.getMessage());
    }

    @Test
    void aKeyIsNeverNull() {
        assertThrows(NullPointerException.class, () -> PanelSample.requireKey(null));
    }
}
