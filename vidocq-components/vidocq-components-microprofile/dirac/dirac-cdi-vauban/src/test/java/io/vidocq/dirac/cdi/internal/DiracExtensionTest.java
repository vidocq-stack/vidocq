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
package io.vidocq.dirac.cdi.internal;

import io.vidocq.dirac.api.DiracException;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * M2 tests — BCE validation of {@code @Gauge} methods, MicroProfile Metrics 5.1.1 spec §4.2.
 */
class DiracExtensionTest {

    // DISCOVERED_GAUGES is a static registry also populated by the CDI integration
    // tests running in the same JVM. Clearing only after each test made the counting
    // assertions depend on surefire's filesystem-dependent class order (green on
    // macOS, red on the CI runner) — clear before as well.
    @BeforeEach
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


