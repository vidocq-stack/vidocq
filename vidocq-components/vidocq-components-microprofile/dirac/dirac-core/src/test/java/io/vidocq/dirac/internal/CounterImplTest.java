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
package io.vidocq.dirac.internal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests M1 — Counter, spec MicroProfile Metrics 5.1.1 §3.1.
 */
class CounterImplTest {

    @Test
    void incrementsMonotonically() {
        var counter = new CounterImpl();

        counter.inc();
        counter.inc(4);

        assertEquals(5, counter.getCount());
    }

    @Test
    void rejectsNegativeIncrement() {
        var counter = new CounterImpl();

        assertThrows(IllegalArgumentException.class, () -> counter.inc(-1));
    }

    @Test
    void remainsCorrectUnder200VirtualThreads() throws Exception {
        var counter = new CounterImpl();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 200)
                    .mapToObj(index -> executor.submit(() -> {
                        for (int i = 0; i < 1_000; i++) {
                            counter.inc();
                        }
                    }))
                    .toList();

            for (var future : futures) {
                future.get();
            }
        }

        assertEquals(200_000, counter.getCount());
    }
}

