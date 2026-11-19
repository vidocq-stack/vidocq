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

import io.vidocq.heisenberg.internal.ConfigResolver;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * MP Fault Tolerance 4.1, "Fault Tolerance Interceptor(s)" (heisenberg#66):
 * {@code mp.fault.tolerance.interceptor.priority} sets the priority of the Fault Tolerance interceptor, whatever
 * the value. The deployment has one Fault Tolerance interceptor, {@link FaultToleranceInterceptor}, which the
 * configured value moves: 5000, above the interceptors of the test, and 3850, the value the TCK checks.
 *
 * <p>The priority is observed through the order of the interceptors: {@link Early} has priority 3900, between
 * the TCK's 3850 and the default 4010; {@link Between} has priority 4500, between the default 4010 and the
 * configured 5000. A retry calls {@code proceed()} once per attempt, so an interceptor that runs after the Fault
 * Tolerance interceptor is invoked once per attempt, and one that runs before it once.
 */
class InterceptorPriorityConfigTest {

    private static final String PRIORITY = "mp.fault.tolerance.interceptor.priority";
    private static final int CONFIGURED = 5000;
    private static final int TCK_PRIORITY = 3850;
    private static final int ATTEMPTS = 4;

    private VaubanContainer container;

    @BeforeEach
    void reset() {
        Early.CALLS.set(0);
        Between.CALLS.set(0);
        FailingBean.CALLS.set(0);
        PriorityAtEnhancement.SEEN.set(0);
    }

    @AfterEach
    void restore() {
        if (container != null) {
            container.close();
        }
        System.clearProperty(PRIORITY);
        ConfigResolver.configurationChanged();
    }

    @Test
    void byDefaultTheInterceptorRunsBeforeAnInterceptorOfHigherPriority() {
        FailingBean bean = start();

        assertThrows(IllegalStateException.class, bean::call);

        assertEquals(ATTEMPTS, FailingBean.CALLS.get(), "the method is retried: Fault Tolerance applies");
        assertEquals(ATTEMPTS, Between.CALLS.get(),
                "an interceptor of priority 4500 runs once per attempt, after the Fault Tolerance interceptor at 4010");
    }

    /**
     * Failed on its last assertion, {@code expected: <1> but was: <4>}, until Vauban applied the priority
     * (BUG-019): it dropped a {@code @Priority} that an {@code @Enhancement} adds to an interceptor class with
     * {@code ClassConfig.addAnnotation(Annotation)}, fixed there as its BUG-20261113-12.
     */
    @Test
    void theConfiguredPriorityOrdersTheInterceptorAfterAnInterceptorOfLowerPriority() {
        // A system property, as a deployment sets it: every MicroProfile Config sees it, whichever the class loader
        System.setProperty(PRIORITY, String.valueOf(CONFIGURED));
        ConfigResolver.configurationChanged();
        FailingBean bean = start();

        assertThrows(IllegalStateException.class, bean::call);

        assertEquals(ATTEMPTS, FailingBean.CALLS.get(), "the method is retried: Fault Tolerance applies");
        assertEquals(CONFIGURED, PriorityAtEnhancement.SEEN.get(),
                "the configuration is readable while the container runs the @Enhancement methods");
        assertEquals(1, Between.CALLS.get(),
                "an interceptor of priority 4500 runs once, before the Fault Tolerance interceptor configured at "
                        + CONFIGURED + "; " + ATTEMPTS + " means the latter kept its default priority 4010");
    }

    /**
     * BUG-020: two deployments in the same JVM, under the same {@code Config}, the way the TCK runs its archives.
     * Nothing tells {@code ConfigResolver} that the second one starts before its extensions run: the priority must
     * still be the one the second deployment configures, not the one the calls of the first left behind.
     */
    @Test
    void aDeploymentReadsItsOwnPriorityAfterAnotherDeployment() {
        FailingBean first = start();
        assertThrows(IllegalStateException.class, first::call);
        container.close();
        Between.CALLS.set(0);
        FailingBean.CALLS.set(0);

        System.setProperty(PRIORITY, String.valueOf(CONFIGURED));
        FailingBean second = start();

        assertThrows(IllegalStateException.class, second::call);

        assertEquals(ATTEMPTS, FailingBean.CALLS.get(), "the method is retried: Fault Tolerance applies");
        assertEquals(1, Between.CALLS.get(),
                "an interceptor of priority 4500 runs once, before the Fault Tolerance interceptor configured at "
                        + CONFIGURED + "; " + ATTEMPTS + " means the latter kept the priority of the first deployment");
    }

    /**
     * The scenario of the TCK's priority test, on the one interceptor: no other interceptor declares 3850, so the
     * policies apply only if the configured value moves {@link FaultToleranceInterceptor} there.
     */
    @Test
    void theTckPriorityOrdersTheInterceptorBeforeAnInterceptorOfHigherPriority() {
        System.setProperty(PRIORITY, String.valueOf(TCK_PRIORITY));
        ConfigResolver.configurationChanged();
        FailingBean bean = start();

        assertThrows(IllegalStateException.class, bean::call);

        assertEquals(ATTEMPTS, FailingBean.CALLS.get(), "the method is retried: Fault Tolerance applies");
        assertEquals(ATTEMPTS, Early.CALLS.get(),
                "an interceptor of priority 3900 runs once per attempt, after the Fault Tolerance interceptor configured"
                        + " at " + TCK_PRIORITY + "; 1 means the latter kept its default priority 4010");
    }

    /** A container with what a deployment has: both extensions, the interceptor, the state registries. */
    private FailingBean start() {
        container = VaubanContainer.builder()
                .addBeanClass(HeisenbergExtension.class)
                .addBeanClass(InterceptorPriorityExtension.class)
                .addBeanClass(PriorityAtEnhancement.class)
                .addBeanClass(FaultToleranceInterceptor.class)
                .addBeanClass(StateRegistryBean.class)
                .addBeanClass(BulkheadStateRegistryBean.class)
                .addBeanClass(Early.class)
                .addBeanClass(Between.class)
                .addBeanClass(FailingBean.class)
                .build();
        return container.select(FailingBean.class);
    }

    /** What {@code ConfigResolver} answers while the container runs the {@code @Enhancement} methods. */
    public static class PriorityAtEnhancement implements BuildCompatibleExtension {
        static final AtomicInteger SEEN = new AtomicInteger();

        @Enhancement(types = FaultToleranceInterceptor.class)
        public void read(ClassConfig interceptor) {
            SEEN.set(ConfigResolver.readInterceptorPriority());
        }
    }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface Counted {
    }

    @Interceptor
    @Priority(3900)
    @Counted
    public static class Early {
        static final AtomicInteger CALLS = new AtomicInteger();

        @AroundInvoke
        public Object around(InvocationContext context) throws Exception {
            CALLS.incrementAndGet();
            return context.proceed();
        }
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
    public static class FailingBean {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Retry(maxRetries = ATTEMPTS - 1, delay = 0L, jitter = 0L)
        public String call() {
            CALLS.incrementAndGet();
            throw new IllegalStateException("always fails");
        }
    }
}
