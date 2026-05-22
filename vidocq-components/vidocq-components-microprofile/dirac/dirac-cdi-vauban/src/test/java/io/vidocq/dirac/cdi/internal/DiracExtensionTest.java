package io.vidocq.dirac.cdi.internal;

import io.vidocq.dirac.api.DiracException;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests M2 — validation BCE des méthodes {@code @Gauge}, spec MicroProfile Metrics 5.1.1 §4.2.
 */
class DiracExtensionTest {

    @AfterEach
    void cleanup() {
        DiracExtension.clearDiscoveredGauges();
    }

    @Test
    void scansValidGaugeMethod() {
        DiracExtension.scanGaugeMethods(ValidGaugeService.class);

        assertEquals(1, DiracExtension.discoveredGaugeCount());
        var resolved = DiracExtension.discoveredGauges().iterator().next();
        assertEquals("valid.gauge", resolved.metricID().getName());
        assertEquals(MetricRegistry.APPLICATION_SCOPE, resolved.scope());
    }

    @Test
    void rejectsGaugeWithParameters() {
        assertThrows(DiracException.class, () -> DiracExtension.scanGaugeMethods(InvalidParamGaugeService.class));
    }

    @Test
    void rejectsGaugeReturningVoid() {
        assertThrows(DiracException.class, () -> DiracExtension.scanGaugeMethods(InvalidVoidGaugeService.class));
    }

    @Test
    void rejectsGaugeReturningNonNumericType() {
        assertThrows(DiracException.class, () -> DiracExtension.scanGaugeMethods(InvalidTypeGaugeService.class));
    }

    @Test
    void rejectsMalformedGaugeTags() {
        assertThrows(DiracException.class, () -> DiracExtension.scanGaugeMethods(InvalidTagGaugeService.class));
    }

    static class ValidGaugeService {
        @Gauge(name = "valid.gauge", absolute = true, tags = {"kind=unit"}, unit = MetricUnits.NONE)
        Integer current() {
            return 1;
        }
    }

    static class InvalidParamGaugeService {
        @Gauge(unit = MetricUnits.NONE)
        Integer current(int ignored) {
            return ignored;
        }
    }

    static class InvalidVoidGaugeService {
        @Gauge(unit = MetricUnits.NONE)
        void current() {
        }
    }

    static class InvalidTypeGaugeService {
        @Gauge(unit = MetricUnits.NONE)
        String current() {
            return "bad";
        }
    }

    static class InvalidTagGaugeService {
        @Gauge(tags = {"missing-separator"}, unit = MetricUnits.NONE)
        Integer current() {
            return 1;
        }
    }
}


