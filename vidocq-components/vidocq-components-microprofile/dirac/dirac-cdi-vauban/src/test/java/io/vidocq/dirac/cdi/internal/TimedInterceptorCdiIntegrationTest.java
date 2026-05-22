package io.vidocq.dirac.cdi.internal;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.annotation.Timed;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test d'integration M4: {@code @Timed} est applique par Vauban embedded.
 */
class TimedInterceptorCdiIntegrationTest {

    @Test
    void timedInterceptorRecordsInvocationInContainer() {
        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(TimedInterceptor.class, MetricRegistryProducerBean.class, TimedService.class)
                .initialize()) {

            var service = container.select(TimedService.class).get();
            assertEquals("pong", service.ping());
            assertEquals("pong", service.ping());

            var registries = container.select(MetricRegistryProducerBean.class).get();
            var metricId = new MetricID("integration.timed.calls", new Tag("source", "cdi"));
            var timer = registries.applicationRegistry().getTimer(metricId);
            assertEquals(2, timer.getCount());
            assertFalse(timer.getElapsedTime().isNegative());
            assertTrue(timer.getSnapshot().getMean() >= 0d);
        }
    }

    @ApplicationScoped
    static class TimedService {
        @Timed(name = "integration.timed.calls", absolute = true, tags = {"source=cdi"})
        public String ping() {
            return "pong";
        }
    }
}




