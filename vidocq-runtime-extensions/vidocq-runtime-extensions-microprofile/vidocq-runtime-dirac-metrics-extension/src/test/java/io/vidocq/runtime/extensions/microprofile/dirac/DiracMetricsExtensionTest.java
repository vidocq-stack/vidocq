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
package io.vidocq.runtime.extensions.microprofile.dirac;

import io.vidocq.dirac.cdi.internal.MetricRegistryProducerBean;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.Verbosity;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code metrics} section of the startup report: it reads the registries of the existing
 * {@link MetricRegistryProducerBean} bean and never creates it. The live values of the dev console panel are
 * covered by {@code MetricsLivePanelTest} in {@code vidocq-runtime-dirac-metrics-extension-dev}.
 */
class DiracMetricsExtensionTest {

    private final DiracMetricsExtension extension = new DiracMetricsExtension();

    @AfterEach
    void stop() {
        extension.onStop();
    }

    @Test
    void itIsAVidocqExtensionFoundByTheServiceLoaderAndContributesItsSection() {
        assertTrue(ServiceLoader.load(VidocqExtension.class).stream()
                .anyMatch(provider -> provider.type() == DiracMetricsExtension.class));
        assertEquals("metrics", extension.id());
        assertEquals("Metrics (Dirac)", extension.title());
        assertEquals("dirac-metrics", extension.name());
    }

    @Test
    void aProducerNotCreatedYetContributesRegistriesNotCreatedYet() {
        try (VaubanContainer container = started(MetricRegistryProducerBean.class)) {
            RecordingSection section = new RecordingSection();
            extension.contribute(new FakeReportContext(Verbosity.DETAILED), section);

            assertEquals("registries not created yet", section.summary);
            assertNull(existing(container.getBeanManager()), "the report created the producer bean");
        }
    }

    /** A container holding {@code beans}, with the extension started on it. */
    private VaubanContainer started(Class<?>... beans) {
        var builder = VaubanContainer.builder();
        for (Class<?> bean : beans) {
            builder.addBeanClass(bean);
        }
        VaubanContainer container = builder.build();
        extension.onStart(new FakeExtensionContext(container));
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
}
