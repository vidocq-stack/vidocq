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

import io.vidocq.dirac.spi.gen.MetricsCompanion;

import java.util.List;

/**
 * Hand-written replica of what the dirac annotation processor emits for
 * {@link MeteredService} — names/units/tags/scopes fully resolved at compile
 * time, gauges as direct functional accessors. Reference template for the
 * processor output and fixture for the CG-05 golden equivalence test.
 */
public final class MeteredService$$DiracMetrics implements MetricsCompanion {

    @Override
    public Class<?> beanClass() {
        return MeteredService.class;
    }

    @Override
    public List<MetricSpec> timers() {
        return List.of(
                new MetricSpec("io.vidocq.dirac.cdi.internal.MeteredService.t1",
                        "d1", "milliseconds", List.of("region=eu"), "application"));
    }

    @Override
    public List<MetricSpec> counters() {
        return List.of(
                new MetricSpec("hits", "", "none", List.of(), "application"),
                new MetricSpec("io.vidocq.dirac.cdi.internal.MeteredService.MeteredService",
                        "", "none", List.of(), "application"));
    }

    @Override
    public List<GaugeSpec> gauges() {
        return List.of(
                new GaugeSpec("io.vidocq.dirac.cdi.internal.MeteredService.temperature",
                        "", "celsius", List.of("room=lab"), "application",
                        false, bean -> ((MeteredService) bean).temperature()),
                new GaugeSpec("uptime",
                        "", "seconds", List.of(), "application",
                        true, ignored -> MeteredService.uptime()));
    }
}
