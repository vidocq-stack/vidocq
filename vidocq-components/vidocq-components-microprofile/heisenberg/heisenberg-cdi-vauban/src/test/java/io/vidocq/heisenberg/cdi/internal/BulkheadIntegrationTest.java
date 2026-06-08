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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException;
import org.junit.jupiter.api.Test;

/**
 * Bulkhead integration tests — M5.
 * MP FT 4.1 §7 — synchronous mode.
 */
class BulkheadIntegrationTest {

    private final BulkheadStateRegistryBean bulkheadRegistry = new BulkheadStateRegistryBean();
    private final FaultToleranceInterceptor interceptor = new FaultToleranceInterceptor();

    BulkheadIntegrationTest() {
        // Inject registries via reflection
        try {
            var stateField = FaultToleranceInterceptor.class.getDeclaredField("stateRegistry");
            stateField.setAccessible(true);
            stateField.set(interceptor, new StateRegistryBean());

            var bulkheadField = FaultToleranceInterceptor.class.getDeclaredField("bulkheadRegistry");
            bulkheadField.setAccessible(true);
            bulkheadField.set(interceptor, bulkheadRegistry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void bulkheadAllowsConcurrentInvocations() throws Exception {
        BulkheadService target = new BulkheadService();
        Method method = BulkheadService.class.getDeclaredMethod("guarded");
        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[0]);

        Object result = interceptor.around(context);

        assertEquals("ok", result);
    }

    @Test
    void bulkheadThrowsExceptionWhenSaturated() throws Exception {
        BulkheadService target = new BulkheadService();
        Method method = BulkheadService.class.getDeclaredMethod("guarded");

        // Acquire the semaphore to saturate the bulkhead (use canonical PolicyComposer key)
        var sem = bulkheadRegistry.getSemaphore(StateKeys.bean(BulkheadService.class), StateKeys.method(method), 1);
        sem.acquire();

        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[0]);

        assertThrows(BulkheadException.class, () -> interceptor.around(context));

        sem.release();
    }

    @Test
    void fallbackActivatesWhenBulkheadException() throws Exception {
        BulkheadFallbackService target = new BulkheadFallbackService();
        Method method = BulkheadFallbackService.class.getDeclaredMethod("guarded");

        // Saturate bulkhead (use canonical PolicyComposer key)
        var sem = bulkheadRegistry.getSemaphore(StateKeys.bean(BulkheadFallbackService.class), StateKeys.method(method), 1);
        sem.acquire();

        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[0]);

        Object result = interceptor.around(context);
        assertEquals("fallback", result);

        sem.release();
    }

    // ---- Services under test ----

    static class BulkheadService {
        @Bulkhead(value = 10, waitingTaskQueue = 10)
        String guarded() {
            return "ok";
        }
    }

    static class BulkheadFallbackService {
        @Bulkhead(value = 1, waitingTaskQueue = 0)
        @Fallback(fallbackMethod = "recover")
        String guarded() {
            return "main";
        }

        String recover() {
            return "fallback";
        }
    }

}

