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
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The Dirac jars, unchanged, under Weld SE on a class path (vidocq-workspace#15, dirac#23): the
 * build compatible extension binds the interceptors to {@code @Counted} and {@code @Timed} and finds
 * the {@code @Gauge} methods, and the metrics land in the application registry. Nothing generated
 * for Vauban is used.
 */
class WeldPortabilityTest {

    private static WeldContainer container;

    @BeforeAll
    static void start() {
        container = new Weld().initialize();
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.close();
        }
    }

    private static MetricRegistry registry() {
        return container.select(MetricRegistryProducerBean.class).get().registry(MetricRegistry.APPLICATION_SCOPE);
    }

    @Test
    void vaubanIsNotOnTheClassPath() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.vidocq.vauban.core.container.VaubanContainer"));
    }

    @Test
    void countedMethodIsCounted() {
        MeteredService service = container.select(MeteredService.class).get();
        long before = registry().counter("orders.placed").getCount();
        service.placeOrder();
        service.placeOrder();
        assertEquals(before + 2, registry().counter("orders.placed").getCount());
    }

    @Test
    void timedMethodIsTimed() {
        MeteredService service = container.select(MeteredService.class).get();
        long before = registry().timer("orders.priced").getCount();
        service.price();
        assertEquals(before + 1, registry().timer("orders.priced").getCount());
    }

    @Test
    void gaugeIsRegistered() {
        assertEquals(7L, registry().getGauge(new MetricID("orders.queue")).getValue().longValue());
    }
}
