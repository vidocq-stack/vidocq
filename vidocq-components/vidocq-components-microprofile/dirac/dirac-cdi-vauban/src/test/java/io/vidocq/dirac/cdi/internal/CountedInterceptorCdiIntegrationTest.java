package io.vidocq.dirac.cdi.internal;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test d'integration M1: {@code @Counted} est applique par Vauban embedded.
 */
class CountedInterceptorCdiIntegrationTest {

    @AfterEach
    void cleanup() {
        DiracExtension.clearDiscoveredCounters();
    }

    @Test
    void countedInterceptorIncrementsApplicationRegistryInContainer() {
        DiracExtension.scanCountedMethods(CountedService.class);

        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(CountedInterceptor.class, MetricRegistryProducerBean.class,
                        GaugeRegistrationBean.class, CountedService.class)
                .initialize()) {

            var service = container.select(CountedService.class).get();
            assertEquals("pong", service.ping());
            assertEquals("pong", service.ping());

            var registries = container.select(MetricRegistryProducerBean.class).get();
            var metricId = new MetricID("integration.counted.calls", new Tag("source", "cdi"));
            assertEquals(2, registries.applicationRegistry().getCounter(metricId).getCount());
            assertEquals(MetricRegistry.APPLICATION_SCOPE, registries.applicationRegistry().getScope());
        }
    }

    @ApplicationScoped
    static class CountedService {
        @Counted(name = "integration.counted.calls", absolute = true, tags = {"source=cdi"})
        public String ping() {
            return "pong";
        }
    }
}

