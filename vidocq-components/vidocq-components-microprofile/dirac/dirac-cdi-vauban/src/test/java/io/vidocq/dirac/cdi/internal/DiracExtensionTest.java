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

import io.vidocq.dirac.api.DiracException;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.eclipse.microprofile.metrics.annotation.Timed;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 tests — BCE validation of {@code @Gauge} methods, MicroProfile Metrics 5.1.1 spec §4.2.
 */
class DiracExtensionTest {

    @Test
    void scansValidGaugeMethod() {
        var discovered = new DiscoveredMetrics();
        discovered.scanGaugeMethods(ValidGaugeService.class);

        assertEquals(1, discovered.gauges().size());
        var resolved = discovered.gauges().iterator().next();
        assertEquals("valid.gauge", resolved.metricID().getName());
        assertEquals(MetricRegistry.APPLICATION_SCOPE, resolved.scope());
    }

    @Test
    void rejectsGaugeWithParameters() {
        var discovered = new DiscoveredMetrics();
        assertThrows(DiracException.class, () -> discovered.scanGaugeMethods(InvalidParamGaugeService.class));
    }

    @Test
    void rejectsGaugeReturningVoid() {
        var discovered = new DiscoveredMetrics();
        assertThrows(DiracException.class, () -> discovered.scanGaugeMethods(InvalidVoidGaugeService.class));
    }

    @Test
    void rejectsGaugeReturningNonNumericType() {
        var discovered = new DiscoveredMetrics();
        assertThrows(DiracException.class, () -> discovered.scanGaugeMethods(InvalidTypeGaugeService.class));
    }

    @Test
    void rejectsMalformedGaugeTags() {
        var discovered = new DiscoveredMetrics();
        assertThrows(DiracException.class, () -> discovered.scanGaugeMethods(InvalidTagGaugeService.class));
    }

    /** DRC-007: the same metric discovered twice is kept once (records compare tag contents). */
    @Test
    void ingestingTheSameClassTwiceKeepsOneMetricEach() {
        var discovered = new DiscoveredMetrics();
        discovered.scanTimedMethods(TimedCountedService.class);
        discovered.scanTimedMethods(TimedCountedService.class);
        discovered.scanCountedMethods(TimedCountedService.class);
        discovered.scanCountedMethods(TimedCountedService.class);

        assertEquals(1, discovered.timers().size());
        assertEquals(1, discovered.counters().size());
    }

    @Test
    void preRegisteredMetricComparesTagContents() {
        var metadata = Metadata.builder().withName("m").build();
        var first = new DiracExtension.PreRegisteredMetric(metadata, new Tag[]{new Tag("k", "v")}, "application");
        var same = new DiracExtension.PreRegisteredMetric(metadata, new Tag[]{new Tag("k", "v")}, "application");
        var otherTags = new DiracExtension.PreRegisteredMetric(metadata, new Tag[]{new Tag("k", "w")}, "application");
        var otherScope = new DiracExtension.PreRegisteredMetric(metadata, new Tag[]{new Tag("k", "v")}, "vendor");

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, otherTags);
        assertNotEquals(first, otherScope);
        assertNotEquals(first, new DiracExtension.PreRegisteredMetric(Metadata.builder().withName("n").build(),
                new Tag[]{new Tag("k", "v")}, "application"));
        assertNotEquals(first, (Object) "m");
        assertTrue(first.toString().contains("tags=[Tag{k=v}]"), first.toString());
    }

    @Test
    void resolvedGaugeComparesTagContents() {
        var metadata = Metadata.builder().withName("g").build();
        Function<Object, Number> invoker = bean -> 1;
        var id = new MetricID("g", new Tag("k", "v"));
        var first = new DiracExtension.ResolvedGauge(Object.class, null, true, id, metadata,
                new Tag[]{new Tag("k", "v")}, "application", invoker);
        var same = new DiracExtension.ResolvedGauge(Object.class, null, true, id, metadata,
                new Tag[]{new Tag("k", "v")}, "application", invoker);
        var otherTags = new DiracExtension.ResolvedGauge(Object.class, null, true, id, metadata,
                new Tag[]{new Tag("k", "w")}, "application", invoker);
        var otherBean = new DiracExtension.ResolvedGauge(String.class, null, true, id, metadata,
                new Tag[]{new Tag("k", "v")}, "application", invoker);
        var instanceGauge = new DiracExtension.ResolvedGauge(Object.class, null, false, id, metadata,
                new Tag[]{new Tag("k", "v")}, "application", invoker);

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, otherTags);
        assertNotEquals(first, otherBean);
        assertNotEquals(first, instanceGauge);
        assertNotEquals(first, gauge(MethodHandles.zero(int.class), id, metadata, "application", invoker));
        assertNotEquals(first, gauge(null, new MetricID("h"), metadata, "application", invoker));
        assertNotEquals(first, gauge(null, id, Metadata.builder().withName("h").build(), "application", invoker));
        assertNotEquals(first, gauge(null, id, metadata, "vendor", invoker));
        assertNotEquals(first, gauge(null, id, metadata, "application", bean -> 2));
        assertNotEquals(first, (Object) "g");
        assertTrue(first.toString().contains("tags=[Tag{k=v}]"), first.toString());
    }

    private static DiracExtension.ResolvedGauge gauge(MethodHandle handle, MetricID id, Metadata metadata,
                                                      String scope, Function<Object, Number> invoker) {
        return new DiracExtension.ResolvedGauge(Object.class, handle, true, id, metadata,
                new Tag[]{new Tag("k", "v")}, scope, invoker);
    }

    static class TimedCountedService {
        @Timed(name = "twice.timer", absolute = true, tags = {"kind=unit"})
        @Counted(name = "twice.counter", absolute = true, tags = {"kind=unit"})
        void work() {
            // metric target only
        }
    }

    static class ValidGaugeService {
        @Gauge(name = "valid.gauge", absolute = true, tags = {"kind=unit"}, unit = MetricUnits.NONE)
        Integer current() {
            return 1;
        }
    }

    static class InvalidParamGaugeService {
        @Gauge(unit = MetricUnits.NONE)
        Integer current(int ignored) {
            return ignored;
        }
    }

    static class InvalidVoidGaugeService {
        @Gauge(unit = MetricUnits.NONE)
        void current() {
            // invalid gauge: returns nothing
        }
    }

    static class InvalidTypeGaugeService {
        @Gauge(unit = MetricUnits.NONE)
        String current() {
            return "bad";
        }
    }

    static class InvalidTagGaugeService {
        @Gauge(tags = {"missing-separator"}, unit = MetricUnits.NONE)
        Integer current() {
            return 1;
        }
    }
}


