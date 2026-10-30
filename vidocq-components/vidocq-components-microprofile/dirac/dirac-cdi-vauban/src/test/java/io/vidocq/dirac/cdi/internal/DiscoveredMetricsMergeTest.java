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

import org.eclipse.microprofile.metrics.annotation.Counted;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DiracExtension may run twice for one application: at build time, from the codegen bundle, and again when the
 * container starts. Each run registers its own DiscoveredMetrics bean, listing the classes that run saw, so the two
 * beans differ and both stay; GaugeRegistrationBean must register their union, each metric once
 * (BUG-20261030-27 of vidocq).
 */
class DiscoveredMetricsMergeTest {

    static class Extra {
        @Counted(name = "extra", absolute = true)
        public void work() {
            // metric target only
        }
    }

    private static DiscoveredMetrics scanned(Class<?> beanClass) {
        var metrics = new DiscoveredMetrics();
        metrics.scanGaugeMethods(beanClass);
        metrics.scanTimedMethods(beanClass);
        metrics.scanCountedMethods(beanClass);
        return metrics;
    }

    @Test
    void theMetricsOfEveryRunAreMergedEachOnce() {
        var single = scanned(MeteredService.class);
        var fromCompanion = new DiscoveredMetrics();
        fromCompanion.ingestCompanion(new MeteredService$$DiracMetrics());

        var merged = DiscoveredMetrics.merge(List.of(single, fromCompanion, scanned(Extra.class)));

        assertEquals(single.gauges().size(), merged.gauges().size(), "each gauge once: " + merged.gauges());
        assertEquals(single.timers(), merged.timers());
        assertEquals(single.counters().size() + 1, merged.counters().size(), "each counter once, plus extra");
    }

    @Test
    void oneRunIsKeptAsItIs() {
        var single = scanned(MeteredService.class);

        var merged = DiscoveredMetrics.merge(List.of(single));

        assertEquals(single.gauges(), merged.gauges());
        assertEquals(single.timers(), merged.timers());
        assertEquals(single.counters(), merged.counters());
    }
}
