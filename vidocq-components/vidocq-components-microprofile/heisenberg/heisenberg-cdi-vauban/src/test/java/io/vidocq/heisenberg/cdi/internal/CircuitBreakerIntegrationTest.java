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
import java.time.temporal.ChronoUnit;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.junit.jupiter.api.Test;

/**
 * CircuitBreaker integration tests with Fallback and Retry — M4.
 * MP FT 4.1 §5 + §2.5 (composition).
 */
class CircuitBreakerIntegrationTest {

    private final StateRegistryBean stateRegistry = new StateRegistryBean();
    private final FaultToleranceInterceptor interceptor = new FaultToleranceInterceptor();

    CircuitBreakerIntegrationTest() {
        // Inject state registry into interceptor via reflection
        try {
            var field = FaultToleranceInterceptor.class.getDeclaredField("stateRegistry");
            field.setAccessible(true);
            field.set(interceptor, stateRegistry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void closedCircuitBreakerAllowsInvocations() throws Exception {
        CBService target = new CBService();
        Method method = CBService.class.getDeclaredMethod("guarded");
        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[0]);

        Object result = interceptor.around(context);

        assertEquals("ok", result);
    }

    @Test
    void fallbackActivatesWhenCircuitBreakerIsManuallyOpened() throws Exception {
        CBFallbackService target = new CBFallbackService();
        Method method = CBFallbackService.class.getDeclaredMethod("guarded");

        // Manually open the circuit (use canonical PolicyComposer key)
        stateRegistry.setOpen(StateKeys.bean(CBFallbackService.class), StateKeys.method(method));

        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[0]);

        // With circuit OPEN, fallback should activate
        Object result = interceptor.around(context);
        assertEquals("fallback", result);
    }

    @Test
    void circuitBreakerOpenExceptionWhenManuallyOpened() throws Exception {
        CBService target = new CBService();
        Method method = CBService.class.getDeclaredMethod("guarded");

        // Manually open circuit (use canonical PolicyComposer key)
        stateRegistry.setOpen(StateKeys.bean(CBService.class), StateKeys.method(method));

        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[0]);

        assertThrows(CircuitBreakerOpenException.class, () -> interceptor.around(context));
    }

    // ---- Services under test ----

    static class CBService {
        @CircuitBreaker(requestVolumeThreshold = 20, failureRatio = 0.5, delay = 5, delayUnit = ChronoUnit.SECONDS)
        String guarded() {
            return "ok";
        }
    }

    static class CBFallbackService {
        @CircuitBreaker(requestVolumeThreshold = 1, failureRatio = 0.5, delay = 5, delayUnit = ChronoUnit.SECONDS)
        @Fallback(fallbackMethod = "recover")
        String guarded() {
            return "main";
        }

        String recover() {
            return "fallback";
        }
    }

}





