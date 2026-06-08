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

import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * M2 integration test: BCE discovery + gauge registration in APPLICATION registry.
 */
class GaugeCdiIntegrationTest {

    @AfterEach
    void cleanup() {
        DiracExtension.clearDiscoveredGauges();
    }

    @Test
    void registersGaugeInApplicationRegistry() {
        DiracExtension.scanGaugeMethods(GaugeService.class);
        var service = new GaugeService();
        service.setCurrent(42);
        var registries = new MetricRegistryProducerBean();

        DiracExtension.registerDiscoveredGauges(registries.applicationRegistry(), ignored -> service);

        var gauge = registries.applicationRegistry().getGauge(new MetricID("integration.gauge", new Tag("source", "cdi")));
        assertEquals(42, gauge.getValue());
    }

    @Test
    void routesGaugeToVendorScope() {
        DiracExtension.scanGaugeMethods(VendorGaugeService.class);
        var service = new VendorGaugeService();
        service.setCurrent(9);
        var registries = new MetricRegistryProducerBean();

        DiracExtension.registerDiscoveredGauges(registries, ignored -> service);

        var metricId = new MetricID("vendor.gauge", new Tag("scope", "vendor"));
        assertEquals(9, registries.vendorRegistry().getGauge(metricId).getValue());
        assertNull(registries.applicationRegistry().getGauge(metricId));
    }

    static class GaugeService {
        private int current = 5;

        @Gauge(name = "integration.gauge", absolute = true, tags = {"source=cdi"}, unit = MetricUnits.NONE)
        public Integer current() {
            return current;
        }

        void setCurrent(int value) {
            this.current = value;
        }
    }

    static class VendorGaugeService {
        private int current;

        @Gauge(
                name = "vendor.gauge",
                absolute = true,
                tags = {"scope=vendor"},
                scope = MetricRegistry.VENDOR_SCOPE,
                unit = MetricUnits.NONE
        )
        public Integer current() {
            return current;
        }

        void setCurrent(int value) {
            this.current = value;
        }
    }
}





