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
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.eclipse.microprofile.metrics.annotation.Timed;
import org.junit.jupiter.api.Test;

/**
 * DRC-012: an interface is not a bean (CDI 4.1, §3.1.1), so it registers no metric of its own. The containers,
 * Vauban and Weld alike, intercept a default method a bean inherits from its interface through the annotation of the
 * interface's method: its metric is the bean's, named after the interface that declares the method, as the
 * interceptors name it, whether the interface is in the deployment or not.
 */
class InterfaceDefaultMethodMetricCdiIntegrationTest {

    public interface Greeter {
        @Counted(name = "iface.default.counted", absolute = true)
        default String greet() {
            return "hello";
        }

        @Timed(name = "iface.default.timed", absolute = true)
        default String wave() {
            return "bye";
        }

        @Counted
        default String nod() {
            return "yes";
        }

        @Gauge(name = "iface.default.gauge", absolute = true, unit = MetricUnits.NONE)
        default long level() {
            return 7L;
        }
    }

    @ApplicationScoped
    public static class GreeterService implements Greeter {
    }

    /** Overrides a default method without a binding: the interface's annotation is not the bean's. */
    @ApplicationScoped
    public static class OverridingGreeterService implements Greeter {
        @Override
        public String greet() {
            return "hi";
        }
    }

    @Test
    void theDefaultMethodsOfAnInterfaceOutsideTheDeploymentAreMetered() {
        try (SeContainer container = start(GreeterService.class)) {
            assertMeteredOnce(container, container.select(GreeterService.class).get());
        }
    }

    @Test
    void theDefaultMethodsOfAnInterfaceOfTheDeploymentAreMetered() {
        try (SeContainer container = start(Greeter.class, GreeterService.class)) {
            assertMeteredOnce(container, container.select(GreeterService.class).get());
        }
    }

    @Test
    void aDefaultMethodTheBeanOverridesRegistersNoMetric() {
        try (SeContainer container = start(Greeter.class, OverridingGreeterService.class)) {
            assertEquals("hi", container.select(OverridingGreeterService.class).get().greet());
            var registry = container.select(MetricRegistryProducerBean.class).get().applicationRegistry();

            assertNull(registry.getCounter(new MetricID("iface.default.counted")),
                    "the bean overrides the method: the interface's binding is not inherited");
            assertNotNull(registry.getTimer(new MetricID("iface.default.timed")), "the bean inherits wave()");
        }
    }

    private static void assertMeteredOnce(SeContainer container, Greeter greeter) {
        assertEquals("hello", greeter.greet());
        assertEquals("bye", greeter.wave());
        assertEquals("yes", greeter.nod());
        var registry = container.select(MetricRegistryProducerBean.class).get().applicationRegistry();
        var counted = registry.getCounter(new MetricID("iface.default.counted"));
        var named = registry.getCounter(new MetricID(MetricRegistry.name(Greeter.class, "nod")));
        var timed = registry.getTimer(new MetricID("iface.default.timed"));
        assertNotNull(counted, "registered: " + registry.getMetricIDs());
        assertNotNull(named, "named after the interface: " + registry.getMetricIDs());
        assertNotNull(timed, "registered: " + registry.getMetricIDs());
        assertEquals(1, counted.getCount());
        assertEquals(1, named.getCount());
        assertEquals(1, timed.getCount());
        assertEquals(7L, registry.getGauge(new MetricID("iface.default.gauge")).getValue(), "read on the bean");
    }

    private static SeContainer start(Class<?>... beans) {
        return SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(DiracExtension.class, CountedInterceptor.class, TimedInterceptor.class,
                        MetricRegistryProducerBean.class, GaugeRegistrationBean.class)
                .addBeanClasses(beans)
                .initialize();
    }
}
