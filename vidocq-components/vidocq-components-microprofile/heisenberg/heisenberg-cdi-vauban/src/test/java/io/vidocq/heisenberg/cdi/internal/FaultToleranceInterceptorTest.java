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
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

class FaultToleranceInterceptorTest {

    private final FaultToleranceInterceptor interceptor = new FaultToleranceInterceptor();

    @Test
    void delegatesToInvocationContextProceed() throws Exception {
        // MP FT 4.1 §2.5: with no active policy, the interceptor invokes the target directly.
        FakeInvocationContext context = new FakeInvocationContext(() -> "ok");

        Object result = interceptor.around(context);

        assertEquals("ok", result);
        assertEquals(1, context.proceedCalls);
    }

    @Test
    void propagatesExceptionFromInvocationContextProceed() {
        FakeInvocationContext context = new FakeInvocationContext(() -> {
            throw new IllegalStateException("boom");
        });

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> interceptor.around(context));

        assertEquals("boom", error.getMessage());
        assertEquals(1, context.proceedCalls);
    }

    @FunctionalInterface
    private interface ProceedBehavior {
        Object proceed() throws Exception;
    }

    private static final class FakeInvocationContext implements InvocationContext {
        private final ProceedBehavior behavior;
        private Object[] parameters = new Object[0];
        private final Map<String, Object> contextData = new HashMap<>();
        private int proceedCalls;

        private FakeInvocationContext(ProceedBehavior behavior) {
            this.behavior = behavior;
        }

        @Override
        public Object getTarget() {
            return this;
        }

        @Override
        public Object getTimer() {
            return null;
        }

        @Override
        public Method getMethod() {
            return null;
        }

        @Override
        public Constructor<?> getConstructor() {
            return null;
        }

        @Override
        public Object[] getParameters() {
            return parameters;
        }

        @Override
        public void setParameters(Object[] params) {
            this.parameters = params;
        }

        @Override
        public Map<String, Object> getContextData() {
            return contextData;
        }

        @Override
        public Object proceed() throws Exception {
            proceedCalls++;
            return behavior.proceed();
        }

        @Override
        public Set<Annotation> getInterceptorBindings() {
            return Set.of();
        }
    }
}
