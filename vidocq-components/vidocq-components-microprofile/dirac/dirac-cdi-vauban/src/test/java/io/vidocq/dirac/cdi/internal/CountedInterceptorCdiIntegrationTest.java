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

