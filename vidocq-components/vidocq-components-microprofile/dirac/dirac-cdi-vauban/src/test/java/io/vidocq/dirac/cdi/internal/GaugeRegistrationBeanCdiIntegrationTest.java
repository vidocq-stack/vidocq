package io.vidocq.dirac.cdi.internal;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test d'integration M2: enregistrement automatique des gauges via GaugeRegistrationBean.
 */
class GaugeRegistrationBeanCdiIntegrationTest {

    @AfterEach
    void cleanup() {
        DiracExtension.clearDiscoveredGauges();
    }

    @Test
    void registersDiscoveredGaugesOnApplicationStartup() {
        DiracExtension.scanGaugeMethods(StartupGaugeService.class);

        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(MetricRegistryProducerBean.class, GaugeRegistrationBean.class, StartupGaugeService.class)
                .initialize()) {

            var service = container.select(StartupGaugeService.class).get();
            service.setCurrent(21);

            var registries = container.select(MetricRegistryProducerBean.class).get();
            var gauge = registries.applicationRegistry().getGauge(new MetricID("startup.gauge", new Tag("source", "startup")));
            assertEquals(21, gauge.getValue());
        }
    }

    @ApplicationScoped
    static class StartupGaugeService {
        private int current;

        @Gauge(name = "startup.gauge", absolute = true, tags = {"source=startup"}, unit = MetricUnits.NONE)
        public Integer current() {
            return current;
        }

        void setCurrent(int value) {
            this.current = value;
        }
    }
}

