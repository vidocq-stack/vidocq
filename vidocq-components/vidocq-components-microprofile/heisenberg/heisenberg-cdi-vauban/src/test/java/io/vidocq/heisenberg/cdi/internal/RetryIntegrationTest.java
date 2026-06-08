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

import java.io.IOException;
import java.lang.reflect.Method;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.junit.jupiter.api.Test;

class RetryIntegrationTest {

    private final FaultToleranceInterceptor interceptor = new FaultToleranceInterceptor();

    @Test
    void retriesUntilSuccessWhenRetryOnMatches() throws Exception {
        RetryService target = new RetryService();
        Method method = RetryService.class.getDeclaredMethod("call");
        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[0]);

        Object result = interceptor.around(context);

        assertEquals("ok", result);
        assertEquals(3, target.calls);
    }

    @Test
    void triggersFallbackAfterRetryExhaustion() throws Exception {
        RetryFallbackService target = new RetryFallbackService();
        Method method = RetryFallbackService.class.getDeclaredMethod("call");
        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[0]);

        Object result = interceptor.around(context);

        assertEquals("fallback", result);
        assertEquals(3, target.calls);
    }

    static class RetryService {
        private int calls;

        @Retry(maxRetries = 2, retryOn = IOException.class)
        String call() throws IOException {
            calls++;
            if (calls < 3) {
                throw new IOException("temporary");
            }
            return "ok";
        }
    }

    static class RetryFallbackService {
        private int calls;

        @Retry(maxRetries = 2, retryOn = IOException.class)
        @Fallback(fallbackMethod = "recover")
        String call() throws IOException {
            calls++;
            throw new IOException("still failing");
        }

        String recover() {
            return "fallback";
        }
    }
}
