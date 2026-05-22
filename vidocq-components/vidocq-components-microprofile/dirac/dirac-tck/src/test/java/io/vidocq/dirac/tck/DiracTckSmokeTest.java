package io.vidocq.dirac.tck;

import org.eclipse.microprofile.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Smoke test TCK — vérifie que les modules Dirac et la spec MP Metrics 5.1.1 sont
 * accessibles sur le classpath avant de lancer la suite Arquillian complète.
 */
class DiracTckSmokeTest {

    @Test
    void metricsApiOnClasspath() {
        assertNotNull(MetricRegistry.class.getName(),
                "microprofile-metrics-api doit être sur le classpath");
    }
}
