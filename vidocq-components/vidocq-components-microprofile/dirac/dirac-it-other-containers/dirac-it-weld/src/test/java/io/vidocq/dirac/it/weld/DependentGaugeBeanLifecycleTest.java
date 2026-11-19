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
package io.vidocq.dirac.it.weld;

import io.vidocq.dirac.cdi.internal.MetricRegistryProducerBean;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DRC-016 under Weld: the gauge of a {@code @Dependent} bean is read on one instance, which Dirac creates at the first
 * read and destroys when the container stops. Each read used to create an instance that Weld kept until the container
 * stopped: one more instance per read of the gauge.
 */
class DependentGaugeBeanLifecycleTest {

    @Test
    void theDependentBeanOfAGaugeIsCreatedOnceAndDestroyedWithTheContainer() {
        // Counted from here: another test of this module may have run a container with this bean before.
        int createdBefore = DependentLevel.CREATED.get();
        int destroyedBefore = DependentLevel.DESTROYED.get();
        int created;
        int destroyedWhileOpen;
        try (WeldContainer container = new Weld().initialize()) {
            Gauge<?> gauge = container.select(MetricRegistryProducerBean.class).get()
                    .registry(MetricRegistry.APPLICATION_SCOPE).getGauge(new MetricID("orders.dependent.level"));
            gauge.getValue();
            gauge.getValue();
            gauge.getValue();
            created = DependentLevel.CREATED.get() - createdBefore;
            destroyedWhileOpen = DependentLevel.DESTROYED.get() - destroyedBefore;
        }
        int destroyedOnceClosed = DependentLevel.DESTROYED.get() - destroyedBefore;

        assertAll(
                () -> assertEquals(1, created, "instances created by three reads"),
                () -> assertEquals(0, destroyedWhileOpen, "instances destroyed while the container is open"),
                () -> assertEquals(1, destroyedOnceClosed, "instances destroyed once the container is closed"));
    }
}
