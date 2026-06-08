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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests M1 — producers de registres par scope, spec MicroProfile Metrics 5.1.1 §2.
 */
class MetricRegistryProducerBeanTest {

    @Test
    void preloadsJvmBaseMetricsInBaseRegistry() {
        var bean = new MetricRegistryProducerBean();

        var base = bean.baseRegistry();
        assertNotNull(base.getGauge(new MetricID("thread.count")));
        assertNotNull(base.getGauge(new MetricID("memory.usedHeap")));
        assertTrue(base.getNames().size() >= 13);
    }

    @Test
    void exposesOneRegistryPerMicroProfileScope() {
        var bean = new MetricRegistryProducerBean();

        assertEquals(MetricRegistry.APPLICATION_SCOPE, bean.applicationRegistry().getScope());
        assertEquals(MetricRegistry.BASE_SCOPE, bean.baseRegistry().getScope());
        assertEquals(MetricRegistry.VENDOR_SCOPE, bean.vendorRegistry().getScope());
    }

    @Test
    void resolvesRegistriesByScopeName() {
        var bean = new MetricRegistryProducerBean();

        assertSame(bean.applicationRegistry(), bean.registry(MetricRegistry.APPLICATION_SCOPE));
        assertSame(bean.baseRegistry(), bean.registry(MetricRegistry.BASE_SCOPE));
        assertSame(bean.vendorRegistry(), bean.registry(MetricRegistry.VENDOR_SCOPE));
        // MP Metrics 5.1.1 allows custom scopes — registry() creates on demand and returns same instance
        var custom = bean.registry("customScope");
        assertNotNull(custom);
        assertSame(custom, bean.registry("customScope"));
    }
}
