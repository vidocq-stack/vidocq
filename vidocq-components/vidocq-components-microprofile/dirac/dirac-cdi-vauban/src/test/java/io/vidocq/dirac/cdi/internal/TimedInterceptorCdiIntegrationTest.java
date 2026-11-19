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
        // DiracExtension discovers the class itself, in this container (dirac#23).

        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(DiracExtension.class, TimedInterceptor.class, MetricRegistryProducerBean.class,
                        GaugeRegistrationBean.class, TimedService.class)
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




