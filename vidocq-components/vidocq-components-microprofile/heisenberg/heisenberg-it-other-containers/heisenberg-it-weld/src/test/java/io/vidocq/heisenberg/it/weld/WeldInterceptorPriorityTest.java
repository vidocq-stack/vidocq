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
package io.vidocq.heisenberg.it.weld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vidocq.heisenberg.internal.ConfigResolver;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * MP Fault Tolerance 4.1, "Fault Tolerance Interceptor(s)", under Weld SE on a class path (heisenberg#66):
 * {@code mp.fault.tolerance.interceptor.priority} moves the Fault Tolerance interceptor. The build compatible
 * extension that applies it is found through {@code META-INF/services}, and runs when Weld deploys.
 *
 * <p>The priority is observed through the order of the interceptors: {@link Between} has priority 4500, between
 * the default 4010 and the configured 5000. A retry calls {@code proceed()} once per attempt, so an interceptor
 * that runs after the Fault Tolerance interceptor is invoked once per attempt, and one that runs before it once.
 */
class WeldInterceptorPriorityTest {

    private static final String PRIORITY = "mp.fault.tolerance.interceptor.priority";
    private static final int ATTEMPTS = 4;

    @BeforeEach
    void reset() {
        Between.CALLS.set(0);
        FailingService.CALLS.set(0);
    }

    @AfterEach
    void restore() {
        System.clearProperty(PRIORITY);
        // This JVM runs several containers on one configuration: what was read for the previous one is dropped
        ConfigResolver.configurationChanged();
    }

    @Test
    void byDefaultTheInterceptorRunsBeforeAnInterceptorOfHigherPriority() {
        try (WeldContainer container = new Weld().initialize()) {
            FailingService service = container.select(FailingService.class).get();

            assertThrows(IllegalStateException.class, service::call);
        }

        assertEquals(ATTEMPTS, FailingService.CALLS.get());
        assertEquals(ATTEMPTS, Between.CALLS.get());
    }

    @Test
    void theConfiguredPriorityOrdersTheInterceptorAfterAnInterceptorOfLowerPriority() {
        System.setProperty(PRIORITY, "5000");
        ConfigResolver.configurationChanged();
        try (WeldContainer container = new Weld().initialize()) {
            FailingService service = container.select(FailingService.class).get();

            assertThrows(IllegalStateException.class, service::call);
        }

        assertEquals(ATTEMPTS, FailingService.CALLS.get());
        assertEquals(1, Between.CALLS.get());
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Counted {
    }

    @Interceptor
    @Priority(4500)
    @Counted
    public static class Between {
        static final AtomicInteger CALLS = new AtomicInteger();

        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            CALLS.incrementAndGet();
            return context.proceed();
        }
    }

    @Dependent
    @Counted
    public static class FailingService {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Retry(maxRetries = ATTEMPTS - 1, delay = 0L, jitter = 0L)
        public String call() {
            CALLS.incrementAndGet();
            throw new IllegalStateException("always fails");
        }
    }
}
