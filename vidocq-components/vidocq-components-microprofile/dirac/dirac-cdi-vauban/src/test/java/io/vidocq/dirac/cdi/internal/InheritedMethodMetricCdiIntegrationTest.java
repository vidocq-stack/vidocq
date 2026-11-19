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
import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.Function;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.junit.jupiter.api.Test;

/**
 * A {@code @Counted} method a bean inherits from its superclass, and does not override, is counted: CDI 4.1 §4.2
 * inherits the member-level interceptor bindings of a superclass, and MicroProfile Metrics 5.1 registers the metric of
 * an annotated method when the bean is deployed. The metric is registered and counts a call, whether the superclass is
 * in the deployment or only on the class path (DRC-011).
 */
class InheritedMethodMetricCdiIntegrationTest {

    /** Added to the deployment with the bean: the container indexes it. Not a bean itself, as it is abstract. */
    public abstract static class IndexedBase {
        @Counted(name = "inherited.indexed", absolute = true)
        public String ping() {
            return "pong";
        }
    }

    @ApplicationScoped
    public static class IndexedBaseService extends IndexedBase {
    }

    /** Never added to the deployment: the class loader sees it, the container does not index it. */
    public static class UnindexedBase {
        @Counted(name = "inherited.unindexed", absolute = true)
        public String ping() {
            return "pong";
        }
    }

    @ApplicationScoped
    public static class UnindexedBaseService extends UnindexedBase {
    }

    /** Never added to the deployment, like {@link UnindexedBase}. */
    public static class UnindexedGaugeBase {
        @org.eclipse.microprofile.metrics.annotation.Gauge(name = "inherited.gauge", absolute = true,
                unit = MetricUnits.NONE)
        public long level() {
            return 7L;
        }
    }

    @ApplicationScoped
    public static class UnindexedGaugeService extends UnindexedGaugeBase {
    }

    /** DRC-013: a gauge is not intercepted, and its inherited method was left out of every scan. */
    @Test
    void aGaugeOnAMethodInheritedFromASuperclassOutOfTheDeploymentIsRegistered() {
        try (SeContainer container = start(UnindexedGaugeService.class)) {
            assertEquals(7L, container.select(UnindexedGaugeService.class).get().level());
            var registry = container.select(MetricRegistryProducerBean.class).get().applicationRegistry();
            var gauge = registry.getGauge(new MetricID("inherited.gauge"));
            assertNotNull(gauge, "the gauge inherited.gauge is registered");
            assertEquals(7L, gauge.getValue());
        }
    }

    /** A bean whose subclass, a bean too, inherits its gauge: the value tells which of the two is read. */
    @ApplicationScoped
    public static class LevelBean {
        @org.eclipse.microprofile.metrics.annotation.Gauge(name = "parent.level", absolute = true,
                unit = MetricUnits.NONE)
        public long level() {
            return this instanceof LevelHeirBean ? 9L : 5L;
        }
    }

    @ApplicationScoped
    public static class LevelHeirBean extends LevelBean {
    }

    /**
     * DRC-013: the gauge of a bean is read on that bean, not on the subclass that inherits it, as MicroProfile
     * Metrics 5.1's {@code InheritedGaugeMethodBeanTest} expects of a parent bean and its child.
     */
    @Test
    void aGaugeOfABeanThatASubclassInheritsIsReadOnTheBeanThatDeclaresIt() {
        try (SeContainer container = start(LevelBean.class, LevelHeirBean.class)) {
            var registry = container.select(MetricRegistryProducerBean.class).get().applicationRegistry();

            assertEquals(5L, registry.getGauge(new MetricID("parent.level")).getValue());
        }
    }

    /** In the deployment, but not a bean (CDI 4.1, §3.1.1): the binding on its method is not inherited (§4.2). */
    public interface CountedContract {
        @Counted(name = "interface.contract", absolute = true)
        String sign();
    }

    @ApplicationScoped
    public static class ContractService implements CountedContract {
        @Override
        public String sign() {
            return "signed";
        }
    }

    @Test
    void anInterfaceOfTheDeploymentRegistersNoMetric() {
        try (SeContainer container = start(CountedContract.class, ContractService.class)) {
            assertEquals("signed", container.select(ContractService.class).get().sign());
            var registry = container.select(MetricRegistryProducerBean.class).get().applicationRegistry();
            assertNull(registry.getCounter(new MetricID("interface.contract")),
                    "an interface is not a bean: no counter for its method");
        }
    }

    /** An application interceptor binding that carries {@code @Counted} with a relative name. */
    @InterceptorBinding
    @Counted(name = "relative")
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface CountedRelative {
    }

    /** An application interceptor binding that carries {@code @Counted} with its default name. */
    @InterceptorBinding
    @Counted
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface CountedDefault {
    }

    @ApplicationScoped
    public static class RelativeService {
        @CountedRelative
        public String ping() {
            return "pong";
        }
    }

    /** Never added to the deployment. */
    public static class DefaultBase {
        @CountedDefault
        public String ping() {
            return "pong";
        }
    }

    @ApplicationScoped
    public static class DefaultService extends DefaultBase {
    }

    @Test
    void aMethodCountedThroughAnApplicationBindingIsNamedAsTheScanNamesIt() {
        // MicroProfile Metrics 5.1, "Annotated Naming Convention": <class name>.<name> for a relative name.
        try (SeContainer container = start(RelativeService.class)) {
            assertEquals("pong", container.select(RelativeService.class).get().ping());
            var registry = container.select(MetricRegistryProducerBean.class).get().applicationRegistry();
            var counter = registry.getCounter(
                    new MetricID(MetricRegistry.name(RelativeService.class, "relative")));
            assertNotNull(counter);
            assertEquals(1, counter.getCount());
        }
    }

    @Test
    void anInheritedMethodCountedThroughAnApplicationBindingIsNamedAsTheScanNamesIt() {
        // Named after the class that declares the method, as for a method that carries @Counted itself.
        try (SeContainer container = start(DefaultService.class)) {
            assertEquals("pong", container.select(DefaultService.class).get().ping());
            var registry = container.select(MetricRegistryProducerBean.class).get().applicationRegistry();
            var counter = registry.getCounter(
                    new MetricID(MetricRegistry.name(DefaultBase.class, "ping")));
            assertNotNull(counter);
            assertEquals(1, counter.getCount());
        }
    }

    @Test
    void aMetricOnAMethodInheritedFromASuperclassOfTheDeploymentIsRegistered() {
        try (SeContainer container = start(IndexedBase.class, IndexedBaseService.class)) {
            assertCountsOneCall(container, "inherited.indexed",
                    c -> c.select(IndexedBaseService.class).get().ping());
        }
    }

    @Test
    void aMetricOnAMethodInheritedFromASuperclassOutOfTheDeploymentIsRegistered() {
        try (SeContainer container = start(UnindexedBaseService.class)) {
            assertCountsOneCall(container, "inherited.unindexed",
                    c -> c.select(UnindexedBaseService.class).get().ping());
        }
    }

    private static SeContainer start(Class<?>... beans) {
        return SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(DiracExtension.class, CountedInterceptor.class, MetricRegistryProducerBean.class,
                        GaugeRegistrationBean.class)
                .addBeanClasses(beans)
                .initialize();
    }

    private static void assertCountsOneCall(SeContainer container, String name, Function<SeContainer, String> call) {
        var registry = container.select(MetricRegistryProducerBean.class).get().applicationRegistry();
        var counter = registry.getCounter(new MetricID(name));
        assertNotNull(counter, "the counter " + name + " is registered when the bean is deployed");
        assertEquals("pong", call.apply(container));
        assertEquals(1, counter.getCount(), "the counter " + name + " counts the call");
    }
}
