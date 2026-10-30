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

import java.lang.reflect.Method;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the annotations and the configuration say about a guarded method is resolved once, not on every
 * invocation: a {@code @Retry} method that succeeded at once cost about 4 µs and 16 KB per call, reading the
 * annotations along the class hierarchy and nine configuration keys each time (BENCH-20261030-01).
 */
class MethodPoliciesTest {

    static class Service {
        int calls;

        @Retry(maxRetries = 3)
        String call() {
            calls++;
            return "ok";
        }
    }

    static class OtherService extends Service {
    }

    private static Method method(String name) throws NoSuchMethodException {
        return Service.class.getDeclaredMethod(name);
    }

    @Test
    void thePoliciesOfAMethodAreResolvedOnce() throws Exception {
        MethodPolicies first = MethodPolicies.of(method("call"), Service.class);
        assertSame(first, MethodPolicies.of(method("call"), Service.class));
        assertEquals(3, first.retryConfig().maxRetries());
        assertEquals(null, first.timeout());
    }

    @Test
    void aDeploymentThatStartsHasThePoliciesResolvedAgain() throws Exception {
        MethodPolicies before = MethodPolicies.of(method("call"), Service.class);
        ConfigResolver.configurationChanged();
        MethodPolicies after = MethodPolicies.of(method("call"), Service.class);
        assertNotSame(before, after, "the same Config instance may now hold another deployment's values");
        assertSame(after, MethodPolicies.of(method("call"), Service.class));
    }

    @Test
    void eachBeanClassHasItsOwnPolicies() throws Exception {
        assertNotSame(MethodPolicies.of(method("call"), Service.class),
                MethodPolicies.of(method("call"), OtherService.class));
    }

    @Test
    void aRetryMethodThatSucceedsCostsAlmostNothingPerCall() throws Exception {
        Service target = new Service();
        Method call = method("call");
        Object[] noArgs = new Object[0];
        PolicyComposer.Invocation invocation = target::call;
        Runnable once = () -> {
            try {
                PolicyComposer.invoke(invocation, target, call, noArgs);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        once.run();
        long perCall = bytesPerCall(once);
        assertTrue(perCall < 256, "a successful @Retry call allocates " + perCall + " bytes");
        assertTrue(target.calls > 1, "the method ran");
    }

    /** Mean bytes the current thread allocates per call, after a warm-up. HotSpot accounts them exactly;
     *  {@code com.sun.management} is reached through reflection because the module under test reads neither
     *  {@code java.management} nor {@code jdk.management}. */
    private static long bytesPerCall(Runnable call) {
        try {
            Object threads = Class.forName("java.lang.management.ManagementFactory")
                    .getMethod("getThreadMXBean").invoke(null);
            Method allocated = Class.forName("com.sun.management.ThreadMXBean")
                    .getMethod("getCurrentThreadAllocatedBytes");
            for (int i = 0; i < 2_000; i++) call.run();
            long before = (Long) allocated.invoke(threads);
            for (int i = 0; i < 5_000; i++) call.run();
            return ((Long) allocated.invoke(threads) - before) / 5_000;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("com.sun.management.ThreadMXBean is not available", e);
        }
    }
}
