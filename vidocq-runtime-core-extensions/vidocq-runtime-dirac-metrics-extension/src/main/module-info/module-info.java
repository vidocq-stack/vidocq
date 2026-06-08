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
 * <h2>Vidocq Runtime :: Dirac metrics extension</h2>
 *
 * <p><em>wrapper</em> module that activates Dirac (MicroProfile Metrics 5.1) in a vidocq
 * deployment. No own Java class — same strategy as
 * {@code vidocq-runtime-knock-health-extension} (see ADR-002 in the {@code knock} repo).</p>
 *
 * <p>The integration is 100% based on standard SPIs:</p>
 * <ul>
 *   <li>CDI 4.1 BuildCompatibleExtension of {@code io.vidocq.dirac.cdi.vauban}
 *       — discover {@code @Counted/@Timed/@Gauge} and produce the
 *       {@code @ApplicationScoped MetricRegistry} beans (APPLICATION/BASE/VENDOR);</li>
 *   <li>JAX-RS scanning of {@code @Path} beans by
 *       {@code vidocq-runtime-cassini-rest-extension} — automatic mount of
 *       {@code MetricsResource} (@Path("/metrics")).</li>
 * </ul>
 *
 * <p>Exposed endpoint (prefixed by the configured mount): {@code /metrics} (OpenMetrics
 * text by default, JSON on {@code Accept: application/json}).</p>
 */
module io.vidocq.runtime.ext.dirac {
    // Dirac modules — transitively re-exposed so consuming apps can use @Counted/@Timed/@Gauge
    // and inject MetricRegistry without manually declaring requires on Dirac.
    requires transitive io.vidocq.dirac.api;
    requires transitive io.vidocq.dirac.core;
    requires transitive io.vidocq.dirac.cdi.vauban;
    requires transitive io.vidocq.dirac.rest;

    // Cassini REST extension: it scans the dirac JAX-RS resource (@Path("/metrics")).
    requires io.vidocq.runtime.ext.rest.cassini;

    requires jakarta.cdi;
    requires jakarta.ws.rs;
}
