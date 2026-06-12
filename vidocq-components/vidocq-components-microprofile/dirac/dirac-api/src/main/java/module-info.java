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
/**
 * Dirac API: controlled re-exposure of the MicroProfile Metrics 5.1.1 spec
 * and stable public SPI of the Vidocq implementation.
 *
 * <p><strong>JPMS note — possible automatic module without {@code Automatic-Module-Name}</strong> :
 * If {@code microprofile-metrics-api:5.1.1} has neither {@code Automatic-Module-Name} in its
 * {@code MANIFEST.MF} nor {@code module-info.class}, the JPMS name used is
 * {@code microprofile.metrics.api} (derived from the Maven artifact name by Java:
 * strip version + replace {@code -} with {@code .}).
 * The parent POM forces this JAR onto the module-path via {@code target/javamodules/}
 * (see {@code maven-dependency-plugin} in the {@code initialize} phase).</p>
 *
 * <p>Planned content (see ROADMAP.md M1+) :</p>
 * <ul>
 *   <li>Transitive re-export of annotations {@code @Counted}, {@code @Timed}, {@code @Gauge},
 *       {@code @Histogram} and {@code MetricRegistry}.</li>
 *   <li>Stable public types: {@code HistogramSnapshot}, {@code TimerSnapshot}.</li>
 *   <li>{@code DiracContext} — initialization context exposed to core components.</li>
 * </ul>
 */
module io.vidocq.dirac.api {
    requires transitive microprofile.metrics.api;

    exports io.vidocq.dirac.api;
    //CG-05 — compile-time metric descriptors implemented by generated
    //$$DiracMetrics companions (consumed by user modules and dirac-cdi-vauban).
    exports io.vidocq.dirac.spi.gen;
}
