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
package io.vidocq.heisenberg.cdi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.heisenberg.internal.CircuitBreakerState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class StateRegistryBeanTest {

    private static final String BEAN = "io.example.Service";
    private static final String METHOD = "call";

    @Test
    void unknownCircuitStartsClosedWithNoSuccessAndNoOpenTime() {
        StateRegistryBean registry = new StateRegistryBean();

        assertEquals(0, registry.getSuccessesInHalfOpen(BEAN, METHOD));
        assertEquals(0L, registry.getMillisSinceOpen(BEAN, METHOD));
        assertEquals(CircuitBreakerState.CLOSED, registry.getState(BEAN, METHOD));
    }

    @Test
    void walksThroughOpenHalfOpenAndClosed() {
        StateRegistryBean registry = new StateRegistryBean();

        registry.setOpen(BEAN, METHOD);
        assertEquals(CircuitBreakerState.OPEN, registry.getState(BEAN, METHOD));
        assertTrue(registry.getMillisSinceOpen(BEAN, METHOD) >= 0);

        registry.setHalfOpen(BEAN, METHOD);
        registry.recordSuccess(BEAN, METHOD);
        registry.recordSuccess(BEAN, METHOD);
        registry.recordFailure(BEAN, METHOD);
        assertEquals(CircuitBreakerState.HALF_OPEN, registry.getState(BEAN, METHOD));
        assertEquals(2, registry.getSuccessesInHalfOpen(BEAN, METHOD));

        registry.setClosed(BEAN, METHOD);
        assertEquals(CircuitBreakerState.CLOSED, registry.getState(BEAN, METHOD));
        assertEquals(0, registry.getSuccessesInHalfOpen(BEAN, METHOD));
    }

    @Test
    void circuitsAreKeyedByBeanClassAndMethod() {
        StateRegistryBean registry = new StateRegistryBean();

        registry.setOpen(BEAN, METHOD);

        assertEquals(CircuitBreakerState.CLOSED, registry.getState(BEAN, "other"));
        assertEquals(CircuitBreakerState.CLOSED, registry.getState("io.example.Other", METHOD));
    }

    @Test
    void concurrentSuccessesAreAllCounted() throws InterruptedException {
        StateRegistryBean registry = new StateRegistryBean();
        registry.setHalfOpen(BEAN, METHOD);
        int threads = 16;
        int perThread = 5_000;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            workers.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int n = 0; n < perThread; n++) {
                    registry.recordSuccess(BEAN, METHOD);
                    registry.recordFailure(BEAN, METHOD);
                }
            }));
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }

        assertEquals(threads * perThread, registry.getSuccessesInHalfOpen(BEAN, METHOD));
    }
}
