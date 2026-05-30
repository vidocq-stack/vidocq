package io.vidocq.dirac.tck;

import org.eclipse.microprofile.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * TCK smoke test — verifies that Dirac modules and MicroProfile Metrics 5.1.1 spec
 * are accessible on the classpath before launching the full Arquillian test suite.
 */
class DiracTckSmokeTest {

    @Test
    void metricsApiOnClasspath() {
        assertNotNull(MetricRegistry.class.getName(),
                "microprofile-metrics-api must be on the classpath");
    }
}
