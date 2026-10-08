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
package io.vidocq.dirac.spi.gen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Invariants of the compile-time metric descriptors (codegen audit CG-05) —
 * literal, reflection-free metadata emitted by the Dirac annotation processor
 * inside generated {@code $$DiracMetrics} companions, consumed by
 * {@code DiracExtension} instead of the startup annotation scan.
 *
 * <p>Lives in dirac-core (not dirac-api) because dirac-api has no testCompile
 * Java Modules workaround — dirac-core re-exports the SPI transitively.</p>
 */
class MetricsCompanionTest {

    @Test
    void metricSpec_copiesTagList() {
        var tags = new ArrayList<>(List.of("region=eu"));
        var spec = new MetricsCompanion.MetricSpec("a.b.count", "desc", "none", tags, "application");
        tags.clear();
        assertEquals(List.of("region=eu"), spec.tags());
        assertThrows(UnsupportedOperationException.class, () -> spec.tags().add("x=y"));
    }

    @Test
    void gaugeSpec_invokesItsFunctionalAccessor() {
        var spec = new MetricsCompanion.GaugeSpec(
                "temp", "", "celsius", List.of(), "application",
                false, bean -> ((Number) bean).intValue() + 1);
        assertEquals(42, spec.invoker().apply(41).intValue());
        assertFalse(spec.staticMethod());
    }

    @Test
    void gaugeSpec_staticAccessorIgnoresBean() {
        var spec = new MetricsCompanion.GaugeSpec(
                "uptime", "", "seconds", List.of(), "vendor",
                true, ignored -> 7L);
        assertEquals(7L, spec.invoker().apply(null));
        assertTrue(spec.staticMethod());
        assertEquals("vendor", spec.scope());
    }
}
