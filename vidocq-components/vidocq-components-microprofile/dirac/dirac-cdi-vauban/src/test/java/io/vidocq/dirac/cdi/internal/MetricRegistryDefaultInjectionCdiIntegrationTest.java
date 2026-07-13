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
import jakarta.inject.Inject;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.annotation.Metric;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * MP Metrics 5.1 spec §"MetricRegistry": "The MetricRegistry of the application
 * scope can be injected: {@code @Inject MetricRegistry metricRegistry;}" — a plain,
 * unqualified injection point must resolve to the application-scope registry.
 *
 * <p>The TCK ({@code CounterFieldBeanTest} and most CDI suites) relies on this
 * plain injection. Since {@code @RegistryScope} is a CDI qualifier, the producer
 * must also carry {@code @Default} to satisfy unqualified injection points.</p>
 */
class MetricRegistryDefaultInjectionCdiIntegrationTest {

    /**
     * MP Metrics 5.1: a {@code Gauge} declared by a bean method can be injected
     * with {@code @Inject @Metric} (TCK {@code GaugeInjectionBeanTest}). The
     * lookup is lazy: the gauge is registered at startup by the gauge owner and
     * the injected handle forwards to the registry.
     */
    @Test
    void gaugeInjectionForwardsToRegisteredGauge() {
        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(MetricRegistryProducerBean.class, GaugeConsumerTestBean.class)
                .initialize()) {

            MetricRegistryProducerBean producer =
                    container.select(MetricRegistryProducerBean.class).get();
            producer.applicationRegistry().gauge("test.gauge", () -> 42L);

            GaugeConsumerTestBean consumer = container.select(GaugeConsumerTestBean.class).get();
            assertNotNull(consumer.gauge(), "@Inject @Metric Gauge must resolve");
            assertEquals(42L, consumer.gauge().getValue(),
                    "the injected gauge must forward to the registered gauge");
        }
    }

    @Test
    void plainInjectMetricRegistryResolvesApplicationScope() {
        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(MetricRegistryProducerBean.class)
                .initialize()) {

            MetricRegistry registry = container.select(MetricRegistry.class).get();
            assertNotNull(registry, "plain @Inject MetricRegistry must resolve (@Default)");

            MetricRegistry application = container.select(MetricRegistryProducerBean.class)
                    .get().applicationRegistry();
            assertSame(application, registry,
                    "the unqualified registry must be the application-scope one");
        }
    }
}
