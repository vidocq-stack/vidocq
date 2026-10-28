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

import jakarta.interceptor.InvocationContext;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.junit.jupiter.api.Test;

/**
 * MP Fault Tolerance 4.1 §2.4: the interceptor bound at priority 3850 applies the policies only when
 * {@code mp.fault.tolerance.interceptor.priority} selects that priority; otherwise it only proceeds.
 */
class FaultTolerancePriority3850InterceptorTest {

    private static final String PRIORITY = "mp.fault.tolerance.interceptor.priority";
    private static final String RECOVERED = "recovered";

    /** The guarded bean: retried up to twice, without delay. */
    public static class FlakyService {
        @Retry(maxRetries = 2, delay = 0, jitter = 0)
        public String call() {
            return RECOVERED;
        }
    }

    private static FaultTolerancePriority3850Interceptor interceptor() {
        FaultTolerancePriority3850Interceptor interceptor = new FaultTolerancePriority3850Interceptor();
        interceptor.stateRegistry = new StateRegistryBean();
        interceptor.bulkheadRegistry = new BulkheadStateRegistryBean();
        return interceptor;
    }

    /** An invocation of {@link FlakyService#call()} whose first {@code proceed} fails. */
    private static InvocationContext flakyInvocation(AtomicInteger proceeds) throws NoSuchMethodException {
        FlakyService target = new FlakyService();
        Method method = FlakyService.class.getMethod("call");
        return (InvocationContext) Proxy.newProxyInstance(InvocationContext.class.getClassLoader(),
                new Class<?>[]{InvocationContext.class}, (self, invoked, args) -> switch (invoked.getName()) {
                    case "getTarget" -> target;
                    case "getMethod" -> method;
                    case "getParameters" -> new Object[0];
                    case "getContextData" -> Map.of();
                    case "proceed" -> {
                        if (proceeds.incrementAndGet() == 1) {
                            throw new IllegalStateException("first call fails");
                        }
                        yield target.call();
                    }
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    case "toString" -> "FlakyInvocation";
                    default -> throw new UnsupportedOperationException(invoked.toString());
                });
    }

    @Test
    void appliesTheRetryWhenPriority3850IsConfigured() throws Exception {
        AtomicInteger proceeds = new AtomicInteger();
        InvocationContext context = flakyInvocation(proceeds);

        Object result;
        try (AutoCloseable _ = TestConfigs.install(Map.of(PRIORITY, "3850"))) {
            result = interceptor().around(context);
        }

        assertEquals(RECOVERED, result);
        assertEquals(2, proceeds.get());
    }

    @Test
    void onlyProceedsUnderAnotherPriority() throws Exception {
        AtomicInteger proceeds = new AtomicInteger();
        InvocationContext context = flakyInvocation(proceeds);
        FaultTolerancePriority3850Interceptor interceptor = interceptor();

        assertThrows(IllegalStateException.class, () -> interceptor.around(context));

        assertEquals(1, proceeds.get());
    }
}
