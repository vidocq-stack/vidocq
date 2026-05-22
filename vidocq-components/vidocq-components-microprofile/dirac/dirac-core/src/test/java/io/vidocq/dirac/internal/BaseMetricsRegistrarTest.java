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


