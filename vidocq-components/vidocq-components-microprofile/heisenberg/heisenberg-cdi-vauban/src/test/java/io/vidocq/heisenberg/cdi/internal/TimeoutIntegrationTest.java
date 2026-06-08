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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.temporal.ChronoUnit;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.junit.jupiter.api.Test;

class TimeoutIntegrationTest {

    private final FaultToleranceInterceptor interceptor = new FaultToleranceInterceptor();

    @Test
    void timeoutAnnotationTriggersTimeoutExceptionOnSlowMethod() throws Exception {
        // §4: a method that exceeds the @Timeout value must throw TimeoutException.
        TimeoutService target = new TimeoutService();
        Method method = TimeoutService.class.getDeclaredMethod("slow");
        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[0]);

        assertThrows(TimeoutException.class, () -> interceptor.around(context));
    }

    @Test
    void fastMethodCompletesWithinTimeoutWithoutException() throws Exception {
        // §4: a method that finishes before the deadline returns normally.
        FastService target = new FastService();
        Method method = FastService.class.getDeclaredMethod("fast");
        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[0]);

        Object result = interceptor.around(context);

        assertEquals("done", result);
    }

    @Test
    void timeoutAppliesPerRetryAttempt() throws Exception {
        // §4.1: timeout applies to each individual attempt.
        // Each attempt exceeds the timeout → maxRetries exhausted → TimeoutException.
        TimeoutRetryService target = new TimeoutRetryService();
        Method method = TimeoutRetryService.class.getDeclaredMethod("call");
        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[0]);

        assertThrows(TimeoutException.class, () -> interceptor.around(context));
        // 1 original attempt + 2 retries = 3 calls total
        assertEquals(3, target.attempts);
    }

    @Test
    void fallbackActivatesAfterTimeoutWhenFallbackIsDeclared() throws Exception {
        // §4 + §6: TimeoutException triggers the fallback if it is declared.
        TimeoutFallbackService target = new TimeoutFallbackService();
        Method method = TimeoutFallbackService.class.getDeclaredMethod("slow");
        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[0]);

        Object result = interceptor.around(context);

        assertEquals("fallback-on-timeout", result);
    }

    // ---- Test services ----

    static class TimeoutService {
        @Timeout(value = 100, unit = ChronoUnit.MILLIS)
        String slow() throws Exception {
            Thread.sleep(500);
            return "too-late";
        }
    }

    static class FastService {
        @Timeout(value = 500, unit = ChronoUnit.MILLIS)
        String fast() {
            return "done";
        }
    }

    static class TimeoutRetryService {
        int attempts;

        @Timeout(value = 100, unit = ChronoUnit.MILLIS)
        @Retry(maxRetries = 2, retryOn = TimeoutException.class)
        String call() throws Exception {
            attempts++;
            Thread.sleep(500);
            return "too-late";
        }
    }

    static class TimeoutFallbackService {
        @Timeout(value = 100, unit = ChronoUnit.MILLIS)
        @Fallback(fallbackMethod = "recover")
        String slow() throws Exception {
            Thread.sleep(500);
            return "too-late";
        }

        String recover() {
            return "fallback-on-timeout";
        }
    }
}

