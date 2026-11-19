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

import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DiracExtension may run twice for one application: at build time, from the codegen bundle, and again when the
 * container starts. Each run registers its own DiscoveredMetrics bean, listing the classes that run saw, so the two
 * beans differ and both stay; GaugeRegistrationBean must register their union, each metric once
 * (BUG-20261030-27 of vidocq).
 */
class DiscoveredMetricsMergeTest {

    static class Extra {
        @Counted(name = "extra", absolute = true)
        public void work() {
            // metric target only
        }
    }

    /** A bean whose subclass inherits its gauge. */
    static class LevelBean {
        @Gauge(unit = MetricUnits.NONE)
        public long level() {
            return 1;
        }
    }

    static class LevelHeir extends LevelBean {
    }

    /** Not a bean: two unrelated beans inherit its gauge. */
    abstract static class SharedLevel {
        @Gauge(name = "shared.level", absolute = true, unit = MetricUnits.NONE)
        public long level() {
            return 2;
        }
    }

    static class FirstLevelHeir extends SharedLevel {
    }

    static class SecondLevelHeir extends SharedLevel {
    }

    private static DiscoveredMetrics scanned(Class<?> beanClass) {
        var metrics = new DiscoveredMetrics();
        metrics.scanGaugeMethods(beanClass);
        metrics.scanTimedMethods(beanClass);
        metrics.scanCountedMethods(beanClass);
        return metrics;
    }

    @Test
    void theMetricsOfEveryRunAreMergedEachOnce() {
        var single = scanned(MeteredService.class);
        var fromCompanion = new DiscoveredMetrics();
        fromCompanion.ingestCompanion(new MeteredService$$DiracMetrics());

        var merged = DiscoveredMetrics.merge(List.of(single, fromCompanion, scanned(Extra.class)));

        assertEquals(single.gauges().size(), merged.gauges().size(), "each gauge once: " + merged.gauges());
        assertEquals(single.timers(), merged.timers());
        assertEquals(single.counters().size() + 1, merged.counters().size(), "each counter once, plus extra");
    }

    /**
     * DRC-013: a bean's gauge, named after its class, is inherited by its subclass under that name. The gauge is read
     * on the bean that declares it, whichever bean was scanned first, as MicroProfile Metrics 5.1's
     * {@code InheritedGaugeMethodBeanTest} expects of a parent bean and its child.
     */
    @Test
    void aGaugeIsReadOnTheBeanThatDeclaresItNotOnTheSubclassThatInheritsIt() {
        var name = MetricRegistry.name(LevelBean.class, "level");
        for (var order : List.of(List.of(LevelBean.class, LevelHeir.class), List.of(LevelHeir.class, LevelBean.class))) {
            var runs = order.stream().map(DiscoveredMetricsMergeTest::scanned).toList();

            var gauges = DiscoveredMetrics.merge(runs).gauges();

            assertEquals(1, gauges.size(), "one gauge " + name + ": " + gauges);
            assertEquals(LevelBean.class, gauges.iterator().next().beanClass(), "scanned in the order " + order);
        }
    }

    /**
     * DRC-013: two unrelated beans inherit one gauge from a class that is not a bean. One gauge has one value: it is
     * read on the bean whose class name comes first, whatever the order of the scans, and a warning names both.
     */
    @Test
    void aGaugeTwoUnrelatedBeansCarryIsReadOnTheFirstByClassNameAndWarned() {
        for (var order : List.of(List.of(FirstLevelHeir.class, SecondLevelHeir.class),
                List.of(SecondLevelHeir.class, FirstLevelHeir.class))) {
            var runs = order.stream().map(DiscoveredMetricsMergeTest::scanned).toList();
            try (var log = new CapturedLog(DiscoveredMetrics.class)) {

                var gauges = DiscoveredMetrics.merge(runs).gauges();

                assertEquals(1, gauges.size(), "one gauge shared.level: " + gauges);
                assertEquals(FirstLevelHeir.class, gauges.iterator().next().beanClass(), "scanned in the order " + order);
                var warnings = log.warnings();
                assertEquals(1, warnings.size(), "one warning");
                assertEquals(List.of("shared.level", FirstLevelHeir.class.getName(), SecondLevelHeir.class.getName()),
                        List.of(warnings.getFirst().getParameters()).subList(0, 3));
            }
        }
    }

    /** DRC-013: the same two beans scanned by two runs of the extension give one warning. */
    @Test
    void aGaugeTwoUnrelatedBeansCarryIsWarnedOnceForTwoRuns() {
        var runs = List.of(scanned(FirstLevelHeir.class), scanned(SecondLevelHeir.class),
                scanned(SecondLevelHeir.class), scanned(FirstLevelHeir.class));
        try (var log = new CapturedLog(DiscoveredMetrics.class)) {

            DiscoveredMetrics.merge(runs);

            assertEquals(1, log.warnings().size(), "one warning");
        }
    }

    @Test
    void oneRunIsKeptAsItIs() {
        var single = scanned(MeteredService.class);

        var merged = DiscoveredMetrics.merge(List.of(single));

        assertEquals(single.gauges(), merged.gauges());
        assertEquals(single.timers(), merged.timers());
        assertEquals(single.counters(), merged.counters());
    }
}
