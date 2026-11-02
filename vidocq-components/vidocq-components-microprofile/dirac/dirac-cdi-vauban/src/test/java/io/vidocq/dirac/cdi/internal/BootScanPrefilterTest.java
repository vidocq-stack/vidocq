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
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * At start-up, only the classes whose declaration names a metric annotation are loaded and scanned by reflection:
 * every bean class of the application used to be, although almost none declares a metric.
 */
class BootScanPrefilterTest {

    /** A stereotype-like annotation carrying {@code @Counted}, which the scan honours one level deep. */
    @Counted(name = "flagged", absolute = true)
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface CountedFlag {
    }

    @ApplicationScoped
    static class PlainService {
        String hello() {
            return "hello";
        }
    }

    @ApplicationScoped
    static class GaugedService {
        @Gauge(name = "prefilter.gauge", absolute = true, unit = "none")
        public Integer current() {
            return 7;
        }
    }

    @ApplicationScoped
    static class FlaggedService {
        @CountedFlag
        public void run() {
            // counted through the meta-annotation
        }
    }

    /** Counted at class level, and @Counted is @Inherited. */
    @Counted(name = "base", absolute = true)
    static class CountedBase {
    }

    @ApplicationScoped
    static class InheritingService extends CountedBase {
        public void work() {
            // counted through the inherited class annotation
        }
    }

    /** Records, for each class the container describes, what the prefilter decides. */
    public static class Probe implements jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension {
        static final java.util.Map<String, Boolean> DECISIONS = new java.util.concurrent.ConcurrentHashMap<>();

        @jakarta.enterprise.inject.build.compatible.spi.Enhancement(types = Object.class, withSubtypes = true)
        public void record(jakarta.enterprise.lang.model.declarations.ClassInfo classInfo) {
            DECISIONS.put(classInfo.name(), DiracExtension.namesAMetric(classInfo));
        }
    }

    @BeforeEach
    void reset() {
        Probe.DECISIONS.clear();
    }

    @Test
    void onlyTheClassesNamingAMetricAreScanned() {
        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(DiracExtension.class, Probe.class, MetricRegistryProducerBean.class,
                        GaugeRegistrationBean.class, PlainService.class, GaugedService.class, FlaggedService.class,
                        InheritingService.class)
                .initialize()) {

            assertEquals(Boolean.FALSE, Probe.DECISIONS.get(PlainService.class.getName()));
            assertEquals(Boolean.FALSE, Probe.DECISIONS.get(MetricRegistryProducerBean.class.getName()));
            assertEquals(Boolean.TRUE, Probe.DECISIONS.get(GaugedService.class.getName()));
            assertEquals(Boolean.TRUE, Probe.DECISIONS.get(FlaggedService.class.getName()));
            assertEquals(Boolean.TRUE, Probe.DECISIONS.get(InheritingService.class.getName()),
                    "an undescribed superclass may carry an inherited metric");
            var registry = container.select(MetricRegistryProducerBean.class).get().applicationRegistry();
            assertNotNull(registry.getGauge(new MetricID("prefilter.gauge")));
            assertNotNull(registry.getCounter(new MetricID("flagged")));
            assertNotNull(registry.getCounter(new MetricID("base")));
        }
    }
}
