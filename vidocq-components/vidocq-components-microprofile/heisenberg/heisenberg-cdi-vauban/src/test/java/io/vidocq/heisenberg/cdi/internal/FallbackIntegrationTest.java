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

import java.lang.reflect.Method;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.faulttolerance.ExecutionContext;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.FallbackHandler;
import org.junit.jupiter.api.Test;

class FallbackIntegrationTest {

    private final FaultToleranceInterceptor interceptor = new FaultToleranceInterceptor();

    @Test
    void appliesFallbackMethodThroughInterceptor() throws Exception {
        MethodFallbackService target = new MethodFallbackService();
        Method method = MethodFallbackService.class.getDeclaredMethod("call", String.class);
        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[]{"alpha"});

        Object result = interceptor.around(context);

        assertEquals("fallback-method:alpha", result);
    }

    @Test
    void appliesFallbackHandlerThroughInterceptor() throws Exception {
        HandlerFallbackService target = new HandlerFallbackService();
        Method method = HandlerFallbackService.class.getDeclaredMethod("call", String.class);
        InvocationContext context = new ReflectiveInvocationContext(target, method, new Object[]{"beta"});

        Object result = interceptor.around(context);

        assertEquals("fallback-handler:beta", result);
    }

    static class MethodFallbackService {
        @Fallback(fallbackMethod = "recover")
        String call(String value) {
            throw new IllegalStateException("boom");
        }

        String recover(String value) {
            return "fallback-method:" + value;
        }
    }

    static class HandlerFallbackService {
        @Fallback(Handler.class)
        String call(String value) {
            throw new IllegalStateException("boom");
        }
    }

    public static class Handler implements FallbackHandler<String> {
        @Override
        public String handle(ExecutionContext context) {
            return "fallback-handler:" + context.getParameters()[0];
        }
    }
}
