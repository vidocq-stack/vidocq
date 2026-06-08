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

import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.Timer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests M1 — MetricRegistry + Counter, spec MicroProfile Metrics 5.1.1 §2 et §3.1.
 */
class MetricRegistryImplTest {

    @Test
    void createsHistogramsWithGetOrCreateSemantics() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        var metricId = new MetricID("payload.size", new Tag("channel", "http"));

        Histogram first = registry.histogram(metricId);
        Histogram second = registry.histogram(metricId);

        first.update(10);
        second.update(5);

        assertSame(first, second);
        assertEquals(2, registry.getHistogram(metricId).getCount());
        assertEquals(15, registry.getHistogram(metricId).getSum());
    }

    @Test
    void createsTimersWithGetOrCreateSemantics() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        var metricId = new MetricID("request.latency", new Tag("channel", "http"));

        Timer first = registry.timer(metricId);
        Timer second = registry.timer(metricId);

        first.update(Duration.ofNanos(10));
        second.update(Duration.ofNanos(5));

        assertSame(first, second);
        assertEquals(2, registry.getTimer(metricId).getCount());
        assertEquals(Duration.ofNanos(15), registry.getTimer(metricId).getElapsedTime());
    }

    @Test
    void createsCountersWithGetOrCreateSemantics() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        var metricId = new MetricID("requests.total", new Tag("method", "GET"));

        Counter first = registry.counter(metricId);
        Counter second = registry.counter(metricId);

        assertSame(first, second);
        assertSame(first, registry.getCounter(metricId));
        assertEquals(Set.of(metricId), registry.getMetricIDs());
        assertEquals(Set.of("requests.total"), registry.getNames());
    }

    @Test
    void storesMetadataWhenCounterIsCreatedFromMetadata() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        var metadata = Metadata.builder()
                .withName("jobs.processed")
                .withDescription("Completed jobs")
                .withUnit(MetricUnits.NONE)
                .build();

        registry.counter(metadata, new Tag("queue", "alpha"));

        assertNotNull(registry.getMetadata("jobs.processed"));
        assertEquals("Completed jobs", registry.getMetadata("jobs.processed").getDescription());
    }

    @Test
    void removeByMetricIdOnlyRemovesMatchingMetric() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        var get = new MetricID("requests.total", new Tag("method", "GET"));
        var post = new MetricID("requests.total", new Tag("method", "POST"));

        registry.counter(get).inc();
        registry.counter(post).inc();

        assertTrue(registry.remove(get));
        assertNull(registry.getMetric(get));
        assertNotNull(registry.getMetric(post));
        assertNotNull(registry.getMetadata("requests.total"));
        assertFalse(registry.remove(get));
    }

    @Test
    void removeByNameRemovesAllMetricIdsSharingTheSameName() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        var get = new MetricID("requests.total", new Tag("method", "GET"));
        var post = new MetricID("requests.total", new Tag("method", "POST"));
        var other = new MetricID("failures.total", new Tag("type", "io"));

        registry.counter(get);
        registry.counter(post);
        registry.counter(other);

        assertTrue(registry.remove("requests.total"));
        assertNull(registry.getMetric(get));
        assertNull(registry.getMetric(post));
        assertNotNull(registry.getMetric(other));
        assertNull(registry.getMetadata("requests.total"));
    }

    @Test
    void exposesSortedReadOnlyCounterViews() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        var alpha = new MetricID("alpha.total");
        var beta = new MetricID("beta.total");

        registry.counter(beta);
        registry.counter(alpha);

        var counters = registry.getCounters();
        assertEquals(new ArrayList<>(Set.of(alpha, beta)).stream().sorted().toList(), new ArrayList<>(counters.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> counters.put(new MetricID("gamma.total"), new CounterImpl()));
    }

    @Test
    void returnsSameCounterInstanceUnder200VirtualThreads() throws Exception {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        var metricId = new MetricID("concurrent.total", new Tag("kind", "test"));
        var identities = ConcurrentHashMap.newKeySet();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 200)
                    .mapToObj(index -> executor.submit(() -> {
                        var counter = registry.counter(metricId);
                        identities.add(System.identityHashCode(counter));
                        counter.inc();
                    }))
                    .toList();

            for (var future : futures) {
                future.get();
            }
        }

        assertEquals(1, identities.size());
        assertEquals(200, registry.getCounter(metricId).getCount());
    }

    @Test
    void reportsItsScope() {
        var registry = new MetricRegistryImpl(MetricRegistry.BASE_SCOPE);

        assertEquals(MetricRegistry.BASE_SCOPE, registry.getScope());
    }
}

