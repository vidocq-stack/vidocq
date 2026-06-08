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

import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests M5 - metriques BASE JVM, spec MicroProfile Metrics 5.1.1 section base metrics.
 */
class BaseMetricsRegistrarTest {

    @Test
    void registersJvmBaseMetricsIntoBaseScopeRegistry() {
        var registry = new MetricRegistryImpl(MetricRegistry.BASE_SCOPE);
        var registrar = new BaseMetricsRegistrar();

        registrar.register(registry);

        assertNotNull(registry.getGauge(new MetricID("gc.total")));
        assertNotNull(registry.getGauge(new MetricID("gc.time")));
        assertNotNull(registry.getGauge(new MetricID("thread.count")));
        assertNotNull(registry.getGauge(new MetricID("thread.daemon.count")));
        assertNotNull(registry.getGauge(new MetricID("thread.max.count")));
        assertNotNull(registry.getGauge(new MetricID("memory.usedHeap")));
        assertNotNull(registry.getGauge(new MetricID("memory.committedHeap")));
        assertNotNull(registry.getGauge(new MetricID("memory.maxHeap")));
        assertNotNull(registry.getGauge(new MetricID("jvm.uptime")));
        assertNotNull(registry.getGauge(new MetricID("classloader.loadedClasses")));
        assertNotNull(registry.getGauge(new MetricID("classloader.unloadedClasses")));
        assertNotNull(registry.getGauge(new MetricID("cpu.availableProcessors")));
        assertNotNull(registry.getGauge(new MetricID("cpu.systemLoadAverage")));

        assertTrue(registry.getNames().size() >= 13);
    }

    @Test
    void isIdempotentWhenCalledTwice() {
        var registry = new MetricRegistryImpl(MetricRegistry.BASE_SCOPE);
        var registrar = new BaseMetricsRegistrar();

        registrar.register(registry);
        var firstSize = registry.getNames().size();
        registrar.register(registry);

        assertTrue(firstSize >= 13);
        assertEquals(firstSize, registry.getNames().size());
    }
}


