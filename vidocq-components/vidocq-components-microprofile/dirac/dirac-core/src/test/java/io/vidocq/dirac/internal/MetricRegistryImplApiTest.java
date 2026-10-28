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
package io.vidocq.dirac.internal;

import io.vidocq.dirac.api.DiracException;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricFilter;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.Timer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MetricRegistry} contract, MicroProfile Metrics 5.1.1 §2.3: every registration
 * overload, filtered views, removal, explicit registration and the validation of names,
 * tags and metric types.
 */
class MetricRegistryImplApiTest {

    private static final String JOBS = "jobs";

    private final MetricRegistryImpl registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);

    @Test
    void metadataOverloadsRegisterUntaggedMetrics() {
        var counter = registry.counter(Metadata.builder().withName("c").withDescription("d").build());
        var histogram = registry.histogram(Metadata.builder().withName("h").build());
        var timer = registry.timer(Metadata.builder().withName("t").build());

        assertSame(counter, registry.getCounter(new MetricID("c")));
        assertSame(histogram, registry.getHistogram(new MetricID("h")));
        assertSame(timer, registry.getTimer(new MetricID("t")));
        assertEquals("d", registry.getMetadata("c").getDescription());
        assertEquals(Set.of("c", "h", "t"), registry.getMetadata().keySet());
    }

    @Test
    void gaugeOverloadsReadTheirSource() {
        var source = new AtomicLong(3);
        Function<AtomicLong, Long> read = AtomicLong::get;

        var byName = registry.gauge("g1", source, read, new Tag("k", "v"));
        var byId = registry.gauge(new MetricID("g2"), source, read);
        var byMetadata = registry.gauge(Metadata.builder().withName("g3").build(), source, read);
        var bySupplier = registry.gauge(new MetricID("g4"), source::get);
        source.set(7);

        for (var gauge : List.of(byName, byId, byMetadata, bySupplier)) {
            assertEquals(7L, gauge.getValue());
        }
        assertSame(byName, registry.getGauge(new MetricID("g1", new Tag("k", "v"))));
        assertEquals(4, registry.getGauges().size());
    }

    @Test
    void gaugeWithoutFunctionFailsOnRead() {
        var gauge = registry.gauge(new MetricID("g"), new AtomicLong(), (Function<AtomicLong, Long>) null);

        assertThrows(NullPointerException.class, gauge::getValue);
    }

    @Test
    void filteredViewsOnlyKeepMatchingMetricsOfTheRequestedType() {
        registry.counter("a.counter");
        registry.histogram("a.histogram");
        registry.timer("a.timer");
        registry.timer("b.timer");
        MetricFilter onlyA = (id, metric) -> id.getName().startsWith("a.");

        assertEquals(1, registry.getHistograms(onlyA).size());
        assertEquals(1, registry.getHistograms().size());
        assertEquals(1, registry.getTimers(onlyA).size());
        assertEquals(2, registry.getTimers().size());
        assertEquals(3, registry.getMetrics(onlyA).size());
        assertEquals(Set.of(new MetricID("a.timer"), new MetricID("b.timer")),
                registry.getMetrics(Timer.class, MetricFilter.ALL).keySet());
        assertEquals(4, registry.getMetrics().size());
    }

    @Test
    void typedLookupOfAnotherTypeReturnsNull() {
        registry.counter(JOBS);

        assertNull(registry.getTimer(new MetricID(JOBS)));
        assertNull(registry.getMetric(new MetricID("missing"), Counter.class));
    }

    @Test
    void removeMatchingRemovesOnlyMatchingMetrics() {
        registry.counter("keep");
        registry.counter("drop.one");
        registry.timer("drop.two");

        registry.removeMatching((id, metric) -> id.getName().startsWith("drop."));

        assertEquals(Set.of("keep"), registry.getNames());
        assertNull(registry.getMetadata("drop.one"));
    }

    @Test
    void explicitRegistrationKeepsTheFirstInstance() {
        var first = new CounterImpl();
        var second = new CounterImpl();

        assertSame(first, registry.register(new MetricID(JOBS, new Tag("q", "a")), first));
        assertSame(first, registry.register(new MetricID(JOBS, new Tag("q", "a")), second));
        assertEquals("none", registry.getMetadata(JOBS).getUnit());

        var described = Metadata.builder().withName("described").withDescription("d").build();
        var registered = registry.register(described, new CounterImpl(), new Tag("q", "b"));
        assertSame(registered, registry.getCounter(new MetricID("described", new Tag("q", "b"))));
        assertEquals("d", registry.getMetadata("described").getDescription());
    }

    @Test
    void reusingANameForAnotherTypeIsRejected() {
        registry.counter(JOBS);
        var jobsId = new MetricID(JOBS);
        var timer = new TimerImpl(JOBS);

        assertThrows(DiracException.class, () -> registry.timer(jobsId));
        assertThrows(DiracException.class, () -> registry.register(jobsId, timer));
    }

    @Test
    void tagKeysMustStayConsistentForOneName() {
        registry.counter(JOBS, new Tag("q", "a"));
        var otherKeys = new Tag("other", "x");

        assertThrows(IllegalArgumentException.class, () -> registry.counter(JOBS, otherKeys));
        assertTrue(registry.getCounters().containsKey(new MetricID(JOBS, new Tag("q", "a"))));
    }

    @Test
    void invalidNamesAndTagsAreRejected() {
        Tag[] nullTag = {null};
        var reserved = new Tag("mp_scope", "x");
        var app = new Tag("mp_app", "x");

        assertThrows(IllegalArgumentException.class, () -> registry.counter(" "));
        assertThrows(NullPointerException.class, () -> registry.counter((String) null));
        assertThrows(IllegalArgumentException.class, () -> registry.counter(JOBS, nullTag));
        assertThrows(IllegalArgumentException.class, () -> registry.counter(JOBS, reserved));
        assertThrows(IllegalArgumentException.class, () -> registry.histogram(JOBS, app));
        assertThrows(IllegalArgumentException.class, () -> new MetricRegistryImpl(" "));
        assertFalse(registry.getNames().contains(JOBS));
    }

    @Test
    void tagsAreSortedAndDeduplicatedByName() {
        Histogram histogram = registry.histogram(JOBS, new Tag("b", "1"), new Tag("a", "2"), new Tag("b", "3"));

        assertSame(histogram, registry.getHistogram(new MetricID(JOBS, new Tag("a", "2"), new Tag("b", "3"))));
    }
}
