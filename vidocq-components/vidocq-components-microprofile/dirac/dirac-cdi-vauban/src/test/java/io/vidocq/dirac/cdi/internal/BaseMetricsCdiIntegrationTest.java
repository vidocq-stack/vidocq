package io.vidocq.dirac.cdi.internal;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.eclipse.microprofile.metrics.MetricID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test d'integration M5: le registre BASE est peuple au demarrage CDI.
 */
class BaseMetricsCdiIntegrationTest {

    @Test
    void baseRegistryIsPrepopulatedAtContainerStartup() {
        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(MetricRegistryProducerBean.class)
                .initialize()) {

            var baseRegistry = container.select(MetricRegistryProducerBean.class).get().baseRegistry();
            assertNotNull(baseRegistry.getGauge(new MetricID("jvm.uptime")));
            assertNotNull(baseRegistry.getGauge(new MetricID("cpu.availableProcessors")));
            assertTrue(baseRegistry.getNames().size() >= 13);
        }
    }
}
