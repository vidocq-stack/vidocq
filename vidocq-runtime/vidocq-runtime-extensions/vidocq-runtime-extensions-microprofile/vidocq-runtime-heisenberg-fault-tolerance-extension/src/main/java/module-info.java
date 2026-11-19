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
 * <h2>Vidocq Runtime :: Fault Tolerance (Heisenberg) extension</h2>
 *
 * <p><em>wrapper</em> module that activates Heisenberg (MicroProfile Fault Tolerance 4.1) in a
 * vidocq deployment. No own Java class — same strategy as
 * {@code vidocq-runtime-dirac-metrics-extension} and
 * {@code vidocq-runtime-knock-health-extension} (see ADR-002 in the {@code knock} repo).</p>
 *
 * <p>The integration is 100% based on standard SPIs:</p>
 * <ul>
 *   <li>CDI 4.1 BuildCompatibleExtension of {@code io.vidocq.heisenberg.cdi.internal}
 *       ({@code HeisenbergExtension}) — discovers {@code @Retry}, {@code @Timeout},
 *       {@code @CircuitBreaker}, {@code @Bulkhead}, {@code @Fallback} and
 *       {@code @Asynchronous} and registers the fault-tolerance interceptors + state
 *       registries.</li>
 * </ul>
 *
 * <p>Fault Tolerance is enforced purely by CDI interceptors — there is no HTTP endpoint to
 * mount, so (unlike the metrics/health wrappers) this module does not require the Cassini REST
 * extension. §9 metrics (Dirac) and §10 telemetry (Humboldt) integration is wired through
 * optional {@code requires static} edges inside {@code heisenberg-cdi-vauban} and activates only
 * when those extensions are also present on the module path.</p>
 */
module io.vidocq.runtime.extensions.microprofile.heisenberg {
    // Heisenberg modules — transitively re-exposed so consuming apps can use the fault-tolerance
    // annotations without manually declaring requires on Heisenberg.
    requires transitive microprofile.fault.tolerance.api;
    requires transitive io.vidocq.heisenberg.api;
    requires transitive io.vidocq.heisenberg.core;
    requires transitive io.vidocq.heisenberg.cdi.vauban;

    requires jakarta.cdi;
}
