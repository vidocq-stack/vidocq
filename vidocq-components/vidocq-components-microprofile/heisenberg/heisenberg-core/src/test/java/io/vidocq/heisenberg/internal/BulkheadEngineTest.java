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
package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException;
import org.junit.jupiter.api.Test;

/**
 * BulkheadEngine tests — M5 TDD.
 * MP FT 4.1 §7 — synchronous mode.
 */
class BulkheadEngineTest {

    @Test
    void bulkheadAllowsConcurrentInvocationsUpToCapacity() throws Exception {
        var config = new BulkheadConfig(2, 0);
        var registry = new TestBulkheadRegistry();
        var engine = new BulkheadEngine(config, registry);

        // Two concurrent acquires should succeed
        Object result1 = engine.execute(() -> "ok1", "TestClass", "testMethod");
        assertEquals("ok1", result1);
    }

    @Test
    void bulkheadThrowsExceptionWhenSaturated() throws Exception {
        var config = new BulkheadConfig(1, 0);
        var registry = new TestBulkheadRegistry();
        var engine = new BulkheadEngine(config, registry);

        // Acquire the single permit
        Semaphore sem = registry.getSemaphore("TestClass", "testMethod", 1);
        sem.acquire();

        // Try-acquire should fail (bulkhead saturated)
        assertThrows(BulkheadException.class, () ->
                engine.execute(() -> "never", "TestClass", "testMethod")
        );

        // Release for cleanup
        sem.release();
    }

    @Test
    void bulkheadAsyncThrowsExceptionWhenSaturated() throws Exception {
        var config = new BulkheadConfig(1, 0);
        var registry = new TestBulkheadRegistry();
        var engine = new BulkheadEngine(config, registry);

        Semaphore sem = registry.getSemaphore("TestClass", "testMethod", 1);
        sem.acquire();

        assertThrows(BulkheadException.class, () ->
                engine.executeAsync(() -> "never", "TestClass", "testMethod")
        );

        sem.release();
    }

    /** §7.2 — In async mode, if all permits are taken but the waiting queue
     *  still has space, the task is queued and executed as soon as a permit is released. */
    @Test
    void bulkheadAsyncQueuesAndProceedsWhenPermitFreed() throws Exception {
        var config = new BulkheadConfig(1, 2);
        var registry = new TestBulkheadRegistry();
        var engine = new BulkheadEngine(config, registry);

        BulkheadStateRegistry.BulkheadState state = registry.getAsyncState("TestClass", "testMethod", 1, 2);
        state.permits().acquire();   // all permits are taken

        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread waiter = Thread.ofVirtual().name("bulkhead-waiter").start(() -> {
            try {
                started.countDown();
                result.set(engine.executeAsync(() -> "queued-ok", "TestClass", "testMethod"));
            } catch (Throwable t) {
                error.set(t);
            }
        });

        assertTrue(started.await(1, TimeUnit.SECONDS));
        // Let the waiter reach permits.acquire()
        Thread.sleep(50);
        // Release the permit → the waiter should proceed
        state.permits().release();

        waiter.join(2000);
        assertEquals("queued-ok", result.get());
        org.junit.jupiter.api.Assertions.assertNull(error.get());
        // Queue emptied: capacity 2 restored
        assertEquals(2, state.waitingQueue().availablePermits());
    }

    /** §7.2 — When permits AND the waiting queue are saturated, BulkheadException is immediate. */
    @Test
    void bulkheadAsyncThrowsWhenQueueAndPermitsSaturated() throws Exception {
        var config = new BulkheadConfig(1, 1);
        var registry = new TestBulkheadRegistry();
        var engine = new BulkheadEngine(config, registry);

        BulkheadStateRegistry.BulkheadState state = registry.getAsyncState("TestClass", "testMethod", 1, 1);
        state.permits().acquire();           // permits saturated
        state.waitingQueue().acquire();      // queue saturated

        BulkheadException ex = assertThrows(BulkheadException.class, () ->
                engine.executeAsync(() -> "never", "TestClass", "testMethod")
        );
        assertTrue(ex.getMessage().contains("queue saturated"));

        state.waitingQueue().release();
        state.permits().release();
    }

    /** §7.2 — The permit is released after execution (fast-path). */
    @Test
    void bulkheadAsyncReleasesPermitAfterFastPath() throws Exception {
        var config = new BulkheadConfig(2, 2);
        var registry = new TestBulkheadRegistry();
        var engine = new BulkheadEngine(config, registry);

        Object result = engine.executeAsync(() -> "fast", "TestClass", "testMethod");
        assertEquals("fast", result);

        BulkheadStateRegistry.BulkheadState state = registry.getAsyncState("TestClass", "testMethod", 2, 2);
        assertEquals(2, state.permits().availablePermits());
        assertEquals(2, state.waitingQueue().availablePermits());
    }

    /** §7.2 — The permit is released even if the invocation throws an exception. */
    @Test
    void bulkheadAsyncReleasesPermitAfterException() {
        var config = new BulkheadConfig(2, 2);
        var registry = new TestBulkheadRegistry();
        var engine = new BulkheadEngine(config, registry);

        assertThrows(IllegalStateException.class, () -> engine.executeAsync(() -> {
            throw new IllegalStateException("boom");
        }, "TestClass", "testMethod"));

        BulkheadStateRegistry.BulkheadState state = registry.getAsyncState("TestClass", "testMethod", 2, 2);
        assertEquals(2, state.permits().availablePermits());
    }

    // Minimal test registry
    static class TestBulkheadRegistry implements BulkheadStateRegistry {
        private final java.util.concurrent.ConcurrentHashMap<String, Semaphore> semaphores =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<String, Semaphore> queues =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Semaphore getSemaphore(String beanClass, String methodName, int permits) {
            String key = beanClass + "#" + methodName;
            return semaphores.computeIfAbsent(key, ignored -> new Semaphore(permits, true));
        }

        @Override
        public BulkheadState getAsyncState(String beanClass, String methodName, int permits, int waitingTaskQueue) {
            String key = beanClass + "#" + methodName;
            Semaphore p = semaphores.computeIfAbsent(key, ignored -> new Semaphore(permits, true));
            Semaphore q = queues.computeIfAbsent(key, ignored -> new Semaphore(waitingTaskQueue, true));
            return new BulkheadState(p, q);
        }
    }
}

