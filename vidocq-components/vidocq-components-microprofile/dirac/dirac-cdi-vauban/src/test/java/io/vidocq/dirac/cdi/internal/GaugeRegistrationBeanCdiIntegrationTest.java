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

