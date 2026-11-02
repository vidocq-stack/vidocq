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
package io.vidocq.dirac.cdi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.vidocq.dirac.spi.gen.MetricsCompanion;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The companions of a source are loaded once for the whole deployment, not once per discovered class. */
class CompanionIndexTest {

    @BeforeEach
    void reset() {
        CompanionRegistry.resetForTests();
    }

    @Test
    void theCompanionsOfASourceAreLoadedOnce() {
        Object source = new Object();
        AtomicInteger loads = new AtomicInteger();

        var first = CompanionRegistry.indexOf(source, () -> {
            loads.incrementAndGet();
            return List.<MetricsCompanion>of();
        });
        var second = CompanionRegistry.indexOf(source, () -> {
            loads.incrementAndGet();
            return List.<MetricsCompanion>of();
        });

        assertSame(first, second);
        assertEquals(1, loads.get());
    }

    @Test
    void onlyTheLastSourcesAreKept() {
        AtomicInteger loads = new AtomicInteger();
        Object first = new Object();
        CompanionRegistry.indexOf(first, () -> { loads.incrementAndGet(); return List.<MetricsCompanion>of(); });
        for (int i = 0; i < 4; i++) {
            CompanionRegistry.indexOf(new Object(), List::<MetricsCompanion>of);
        }

        CompanionRegistry.indexOf(first, () -> { loads.incrementAndGet(); return List.<MetricsCompanion>of(); });

        assertEquals(2, loads.get());
    }
}
