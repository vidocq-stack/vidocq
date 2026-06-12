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

import org.eclipse.microprofile.metrics.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden equivalence proof for CG-05: the hand-written
 * {@link MeteredService$$DiracMetrics} companion (exactly what the dirac
 * annotation processor emits) ingested by {@code DiracExtension} must produce
 * the same discovered metrics — name, description, unit, tags, scope — as the
 * reflective startup scan of {@link MeteredService}.
 */
class CompanionEquivalenceTest {

    @BeforeEach
    void reset() {
        DiracExtension.clearDiscoveredGauges();
        DiracExtension.clearDiscoveredTimers();
        DiracExtension.clearDiscoveredCounters();
        CompanionRegistry.resetForTests();
    }

    private static String canonTags(Tag[] tags) {
        return Arrays.stream(tags)
                .map(t -> t.getTagName() + "=" + t.getTagValue())
                .sorted()
                .collect(Collectors.joining(","));
    }

    private static Set<String> canonMetrics(Set<DiracExtension.PreRegisteredMetric> metrics) {
        return metrics.stream()
                .map(m -> m.metadata().getName() + "|" + m.metadata().getDescription()
                        + "|" + m.metadata().getUnit() + "|" + canonTags(m.tags()) + "|" + m.scope())
                .collect(Collectors.toSet());
    }

    private static Set<String> canonGauges(Set<DiracExtension.ResolvedGauge> gauges) {
        return gauges.stream()
                .map(g -> g.metricID().getName() + "|" + g.metadata().getDescription()
                        + "|" + g.metadata().getUnit() + "|" + canonTags(g.tags())
                        + "|" + g.scope() + "|static=" + g.staticMethod())
                .collect(Collectors.toSet());
    }

    @Test
    void companionIngestion_matchesScan_fieldByField() {
        // Reference: the reflective scan.
        DiracExtension.scanGaugeMethods(MeteredService.class);
        DiracExtension.scanTimedMethods(MeteredService.class);
        DiracExtension.scanCountedMethods(MeteredService.class);
        var scannedTimers = canonMetrics(DiracExtension.discoveredTimers());
        var scannedCounters = canonMetrics(DiracExtension.discoveredCounters());
        var scannedGauges = canonGauges(DiracExtension.discoveredGauges());
        assertFalse(scannedTimers.isEmpty());
        assertFalse(scannedCounters.isEmpty());
        assertEquals(2, scannedGauges.size());

        DiracExtension.clearDiscoveredGauges();
        DiracExtension.clearDiscoveredTimers();
        DiracExtension.clearDiscoveredCounters();

        // Candidate: the compile-time companion.
        DiracExtension.ingestCompanion(new MeteredService$$DiracMetrics());

        assertEquals(scannedTimers, canonMetrics(DiracExtension.discoveredTimers()), "timers diverge");
        assertEquals(scannedCounters, canonMetrics(DiracExtension.discoveredCounters()), "counters diverge");
        assertEquals(scannedGauges, canonGauges(DiracExtension.discoveredGauges()), "gauges diverge");
    }

    @Test
    void companionGaugeAccessors_returnLiveValues() {
        var companion = new MeteredService$$DiracMetrics();
        var instanceGauge = companion.gauges().get(0);
        var staticGauge = companion.gauges().get(1);
        assertEquals(21, instanceGauge.invoker().apply(new MeteredService()));
        assertEquals(99L, staticGauge.invoker().apply(null));
    }

    @Test
    void companionRegistry_resolvesByNamingConvention() {
        var companion = CompanionRegistry.resolve(MeteredService.class);
        assertNotNull(companion, "naming-convention tier must find MeteredService$$DiracMetrics");
        assertEquals(1, CompanionRegistry.preGeneratedHits());
        assertEquals(MeteredService.class, companion.beanClass());
    }

    @Test
    void companionRegistry_fallsBackToNullWithoutArtifact() {
        assertNull(CompanionRegistry.resolve(String.class));
        assertEquals(0, CompanionRegistry.preGeneratedHits());
    }
}
