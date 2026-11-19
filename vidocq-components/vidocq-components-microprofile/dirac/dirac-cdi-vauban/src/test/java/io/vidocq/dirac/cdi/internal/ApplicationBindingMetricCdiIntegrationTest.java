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
package io.vidocq.dirac.cdi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Timed;
import org.junit.jupiter.api.Test;

/**
 * DRC-014: a metric annotation an application interceptor binding carries names the metric as the annotation would on
 * the same element (MicroProfile Metrics 5.1, "Annotated Naming Convention"). The scan registers that name; the
 * interceptors must update it, for a method, an inherited method, a constructor and a class alike. They read the
 * annotation on the element itself and missed the one a binding carries.
 */
class ApplicationBindingMetricCdiIntegrationTest {

    private static final String PACKAGE = ApplicationBindingMetricCdiIntegrationTest.class.getPackageName();

    /** An application interceptor binding that carries {@code @Timed} with a relative name. */
    @InterceptorBinding
    @Timed(name = "relative")
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface TimedRelative {
    }

    /** An application interceptor binding that carries {@code @Timed} with its default name. */
    @InterceptorBinding
    @Timed
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface TimedDefault {
    }

    /** An application interceptor binding that carries {@code @Counted}, on a constructor or a class. */
    @InterceptorBinding
    @Counted(name = "built")
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
    public @interface CountedBuild {
    }

    @ApplicationScoped
    public static class TimedRelativeService {
        @TimedRelative
        public String ping() {
            return "pong";
        }
    }

    /** Never added to the deployment. */
    public static class TimedDefaultBase {
        @TimedDefault
        public String ping() {
            return "pong";
        }
    }

    @ApplicationScoped
    public static class TimedDefaultService extends TimedDefaultBase {
    }

    @Dependent
    public static class BuiltService {
        @CountedBuild
        public BuiltService() {
            // counted through the binding on the constructor
        }
    }

    @Dependent
    @CountedBuild
    public static class ClassBuiltService {
        public String ping() {
            return "pong";
        }
    }

    @Test
    void aMethodTimedThroughAnApplicationBindingIsNamedAsTheScanNamesIt() {
        try (SeContainer container = start(TimedRelativeService.class)) {
            assertEquals("pong", container.select(TimedRelativeService.class).get().ping());

            assertTimedOnce(container, MetricRegistry.name(TimedRelativeService.class, "relative"));
        }
    }

    @Test
    void anInheritedMethodTimedThroughAnApplicationBindingIsNamedAsTheScanNamesIt() {
        // Named after the class that declares the method, as for a method that carries @Timed itself.
        try (SeContainer container = start(TimedDefaultService.class)) {
            assertEquals("pong", container.select(TimedDefaultService.class).get().ping());

            assertTimedOnce(container, MetricRegistry.name(TimedDefaultBase.class, "ping"));
        }
    }

    @Test
    void aConstructorCountedThroughAnApplicationBindingCountsItsCalls() {
        try (SeContainer container = start(BuiltService.class)) {
            assertNotNull(container.select(BuiltService.class).get());

            assertCountedOnce(container, MetricRegistry.name(BuiltService.class, "built"));
        }
    }

    @Test
    void aClassCountedThroughAnApplicationBindingCountsItsConstructorAndItsMethods() {
        try (SeContainer container = start(ClassBuiltService.class)) {
            assertEquals("pong", container.select(ClassBuiltService.class).get().ping());

            assertCountedOnce(container, PACKAGE + ".built." + ClassBuiltService.class.getSimpleName());
            assertCountedOnce(container, PACKAGE + ".built.ping");
        }
    }

    private static SeContainer start(Class<?>... beans) {
        return SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(DiracExtension.class, CountedInterceptor.class, TimedInterceptor.class,
                        MetricRegistryProducerBean.class, GaugeRegistrationBean.class)
                .addBeanClasses(beans)
                .initialize();
    }

    private static void assertCountedOnce(SeContainer container, String name) {
        var registry = container.select(MetricRegistryProducerBean.class).get().applicationRegistry();
        var counter = registry.getCounter(new MetricID(name));
        assertNotNull(counter, "the counter " + name + " is registered: " + registry.getCounters().keySet());
        assertEquals(1, counter.getCount(), "the counter " + name + " counts the call");
    }

    private static void assertTimedOnce(SeContainer container, String name) {
        var registry = container.select(MetricRegistryProducerBean.class).get().applicationRegistry();
        var timer = registry.getTimer(new MetricID(name));
        assertNotNull(timer, "the timer " + name + " is registered: " + registry.getTimers().keySet());
        assertEquals(1, timer.getCount(), "the timer " + name + " times the call");
    }
}
