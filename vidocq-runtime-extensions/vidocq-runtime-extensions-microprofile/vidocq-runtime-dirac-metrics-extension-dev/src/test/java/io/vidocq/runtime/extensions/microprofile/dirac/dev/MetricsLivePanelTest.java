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
package io.vidocq.runtime.extensions.microprofile.dirac.dev;

import io.vidocq.dirac.cdi.internal.MetricRegistryProducerBean;
import io.vidocq.runtime.extensions.microprofile.dirac.live.DiracLiveBean;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code metrics} panel on a real Vauban container: it reads the registries of the existing
 * {@link MetricRegistryProducerBean} and never creates it.
 */
class MetricsLivePanelTest {

    private final MetricsLivePanel panel = new MetricsLivePanel();

    @AfterEach
    void stop() {
        panel.stop();
    }

    @Test
    void itMakesTheMetricsSectionLiveWithNoCharts() {
        MetricsLivePanel panel = new MetricsLivePanel();

        assertEquals("metrics", panel.id());
        assertTrue(panel.charts().isEmpty());
    }

    @Test
    void beforeStartTheSampleIsEmpty() {
        RecordingSample out = new RecordingSample();

        panel.sample(out);

        assertTrue(out.isEmpty());
    }

    @Test
    void aProducerNotCreatedYetIsShownAbsentAndSamplingDoesNotCreateIt() {
        try (VaubanContainer container = started(MetricRegistryProducerBean.class)) {
            RecordingSample out = new RecordingSample();
            panel.sample(out);

            assertEquals("absent", out.kind("registries"));
            assertEquals("not created yet", out.text("registries"));
            assertNull(existing(container.getBeanManager()), "sampling created the producer bean");
        }
    }

    @Test
    void anApplicationCounterIncrementedMovesInThePanel() {
        try (VaubanContainer container = started(MetricRegistryProducerBean.class)) {
            MetricRegistryProducerBean producer = create(container.getBeanManager());
            producer.produceApplicationByType().counter("orders").inc();

            RecordingSample first = new RecordingSample();
            panel.sample(first);
            producer.produceApplicationByType().counter("orders").inc(4);
            RecordingSample second = new RecordingSample();
            panel.sample(second);

            assertEquals(1, first.groups().get("application").number("orders"));
            assertEquals(5, second.groups().get("application").number("orders"));
        }
    }

    @Test
    void aContainerWithoutDiracSaysSo() {
        try (VaubanContainer container = started()) {
            RecordingSample out = new RecordingSample();
            panel.sample(out);

            assertEquals(DiracLiveBean.NOT_DEPLOYED, out.text("registries"));
        }
    }

    @Test
    void afterStopTheSampleIsEmpty() {
        try (VaubanContainer container = started(MetricRegistryProducerBean.class)) {
            create(container.getBeanManager());
            panel.stop();
            RecordingSample out = new RecordingSample();

            panel.sample(out);

            assertTrue(out.isEmpty());
        }
    }

    /** A container holding {@code beans}, with the panel started on it. */
    private VaubanContainer started(Class<?>... beans) {
        var builder = VaubanContainer.builder();
        for (Class<?> bean : beans) {
            builder.addBeanClass(bean);
        }
        VaubanContainer container = builder.build();
        panel.start(new FakeExtensionContext(container));
        return container;
    }

    private static Bean<?> producerBean(BeanManager beans) {
        Bean<?> bean = beans.resolve(beans.getBeans(MetricRegistryProducerBean.class));
        assertNotNull(bean, "the container holds no MetricRegistryProducerBean: the test proves nothing");
        return bean;
    }

    private static Object existing(BeanManager beans) {
        Bean<?> bean = producerBean(beans);
        return beans.getContext(bean.getScope()).get(bean);
    }

    /** Creates the producer bean as the application would, by asking its context for it. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static MetricRegistryProducerBean create(BeanManager beans) {
        Bean bean = producerBean(beans);
        Context context = beans.getContext(bean.getScope());
        return (MetricRegistryProducerBean) context.get(bean, beans.createCreationalContext(bean));
    }
}
