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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import jakarta.enterprise.context.Dependent;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test: a CDI @RequestScoped bean with a
 * @Retry method must have the {@link FaultToleranceInterceptor} triggered by Vauban,
 * and the method must be executed up to the configured number of attempts.
 *
 * <p>Reproduces the scenario that fails in the MicroProfile Fault Tolerance 4.1 TCK
 * ({@code RetryTest.testRetryMaxRetries}) before the "class-level
 * @InterceptorBinding via BCE Enhancement" fix.</p>
 */
class FaultToleranceVaubanWiringTest {

    private io.vidocq.vauban.core.container.VaubanContainer container;

    @AfterEach
    void closeContainer() {
        if (container != null && container.isRunning()) {
            try { container.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    void retryAnnotatedMethodIsInterceptedAndRetried() {
        container = io.vidocq.vauban.core.container.VaubanContainer.builder()
                .addBeanClass(HeisenbergExtension.class)
                .addBeanClass(FaultToleranceInterceptor.class)
                .addBeanClass(StateRegistryBean.class)
                .addBeanClass(BulkheadStateRegistryBean.class)
                .addBeanClass(RetryBean.class)
                .build();

        container.requestContext().activate();

        var bean = container.select(RetryBean.class);
        assertNotNull(bean);
        System.err.println("[Wiring] bean class = " + bean.getClass().getName());
        try {
            var mgrField = bean.getClass().getDeclaredField("$$manager");
            mgrField.setAccessible(true);
            System.err.println("[Wiring] $$manager = " + mgrField.get(bean));
        } catch (NoSuchFieldException e) {
            System.err.println("[Wiring] no $$manager field");
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        try {
            bean.call();
        } catch (RuntimeException expected) {
            // Expected: retries exhausted.
        }

        // @Retry(maxRetries = 3) → 1 initial call + 3 retries = 4 invocations
        assertEquals(4, RetryBean.CALLS.get(),
                "Expected 4 invocations (1 + 3 retries); got " + RetryBean.CALLS.get()
                        + ". If 1, the FaultToleranceInterceptor is not being wired by Vauban.");
    }

    @Dependent
    static class RetryBean {
        static final AtomicInteger CALLS = new AtomicInteger(0);

        @Retry(maxRetries = 3, retryOn = RuntimeException.class, delay = 0L)
        public String call() {
            CALLS.incrementAndGet();
            throw new RuntimeException("boom");
        }
    }
}



