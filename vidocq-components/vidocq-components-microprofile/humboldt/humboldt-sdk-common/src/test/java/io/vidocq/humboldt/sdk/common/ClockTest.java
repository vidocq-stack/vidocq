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

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClockTest {

    @Test
    void system_clock_now_is_close_to_instant_now() {
        long before = Instant.now().toEpochMilli();
        long now = Clock.system().now();
        long after = Instant.now().toEpochMilli();

        long nowMillis = now / 1_000_000L;
        assertTrue(nowMillis >= before - 5, "now() must be >= before: " + nowMillis + " vs " + before);
        assertTrue(nowMillis <= after + 5, "now() must be <= after: " + nowMillis + " vs " + after);
    }

    @Test
    void system_clock_nanoTime_is_monotonic() {
        long t1 = Clock.system().nanoTime();
        long t2 = Clock.system().nanoTime();
        assertTrue(t2 >= t1, "nanoTime() must be monotonic");
    }

    @Test
    void system_singleton_is_stable() {
        assertNotEquals(0, Clock.system().now());
        // same instance returned on each call
        org.junit.jupiter.api.Assertions.assertSame(Clock.system(), Clock.system());
    }
}
