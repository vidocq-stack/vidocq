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

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.junit.jupiter.api.Test;

class PolicyComposerTest {

    @Test
    void delegatesToInvocationAndReturnsItsValue() throws Exception {
        // MP FT 4.1 §2.5: with no active policy, the target method executes as-is.
        Object result = PolicyComposer.invoke(() -> "ok");

        assertEquals("ok", result);
    }

    @Test
    void propagatesInvocationException() {
        // MP FT 4.1 §2.5: outer layers will see the inner-layer exception.
        IOException error = assertThrows(
                IOException.class,
                () -> PolicyComposer.invoke(() -> {
                    throw new IOException("boom");
                })
        );

        assertEquals("boom", error.getMessage());
    }

    @Test
    void appliesFallbackWhenMethodDeclaresFallbackPolicy() throws Exception {
        // MP FT 4.1 §2.5: @Fallback is the outermost layer.
        FallbackService service = new FallbackService();
        Method guardedMethod = FallbackService.class.getDeclaredMethod("guarded");

        Object result = PolicyComposer.invoke(
                () -> {
                    throw new IOException("backend down");
                },
                service,
                guardedMethod,
                new Object[0]
        );

        assertEquals("fallback-value", result);
    }

    @Test
    void keepsFailFastBehaviorWhenNoFallbackIsDeclared() throws Exception {
        PlainService service = new PlainService();
        Method guardedMethod = PlainService.class.getDeclaredMethod("guarded");

        IOException error = assertThrows(
                IOException.class,
                () -> PolicyComposer.invoke(
                        () -> {
                            throw new IOException("still failing");
                        },
                        service,
                        guardedMethod,
                        new Object[0]
                )
        );

        assertEquals("still failing", error.getMessage());
    }

    @Test
    void retriesBeforeReturningSuccessfulResult() throws Exception {
        RetryOnlyService service = new RetryOnlyService();
        Method guardedMethod = RetryOnlyService.class.getDeclaredMethod("guarded");
        AtomicInteger calls = new AtomicInteger();

        Object result = PolicyComposer.invoke(
                () -> {
                    if (calls.getAndIncrement() < 2) {
                        throw new IOException("temporary");
                    }
                    return "eventual-success";
                },
                service,
                guardedMethod,
                new Object[0]
        );

        assertEquals("eventual-success", result);
        assertEquals(3, calls.get());
    }

    @Test
    void fallbackRunsAfterRetryExhaustion() throws Exception {
        RetryWithFallbackService service = new RetryWithFallbackService();
        Method guardedMethod = RetryWithFallbackService.class.getDeclaredMethod("guarded");
        AtomicInteger calls = new AtomicInteger();

        Object result = PolicyComposer.invoke(
                () -> {
                    calls.incrementAndGet();
                    throw new IOException("always failing");
                },
                service,
                guardedMethod,
                new Object[0]
        );

        assertEquals("recovered-after-retry", result);
        assertEquals(3, calls.get());
    }

    /**
     * The binding is added to the whole class, so every method of a guarded bean is intercepted: one without any
     * Fault Tolerance annotation, a fallback method for one, is no FT method and records no metric (heisenberg
     * BUG-014, seen as ft_invocations_total of the fallback method).
     */
    @Test
    void aMethodWithoutFaultToleranceRecordsNoMetric() throws Exception {
        java.util.List<String> recorded = new java.util.ArrayList<>();
        io.vidocq.heisenberg.api.FtMetricsRecorder recorder = (io.vidocq.heisenberg.api.FtMetricsRecorder)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {io.vidocq.heisenberg.api.FtMetricsRecorder.class},
                        (proxy, m, args) -> {
                            recorded.add(m.getName());
                            return null;
                        });
        Method recover = FallbackService.class.getDeclaredMethod("recover");

        Object result = PolicyComposer.invoke(() -> "plain", new FallbackService(), recover, new Object[0],
                null, null, recorder);

        assertEquals("plain", result);
        assertEquals(java.util.List.of(), recorded);
    }

    static class FallbackService {
        @Fallback(fallbackMethod = "recover")
        String guarded() {
            return "unreachable";
        }

        String recover() {
            return "fallback-value";
        }
    }

    static class PlainService {
        String guarded() {
            return "unreachable";
        }
    }

    static class RetryOnlyService {
        @Retry(maxRetries = 2)
        String guarded() {
            return "unreachable";
        }
    }

    static class RetryWithFallbackService {
        @Retry(maxRetries = 2, retryOn = IOException.class)
        @Fallback(fallbackMethod = "recover")
        String guarded() {
            return "unreachable";
        }

        String recover() {
            return "recovered-after-retry";
        }
    }
}

