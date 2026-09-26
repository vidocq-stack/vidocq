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
 * <h2>Vidocq Runtime :: Knock health extension</h2>
 *
 * <p><em>wrapper</em> module that activates Knock (MicroProfile Health 4.0) in a
 * vidocq deployment — see
 * {@code docs/adr/ADR-002-vidocq-runtime-integration-strategy.md} in the repo
 * {@code knock}. Its one class, {@code KnockHealthExtension}, reads the registry
 * for the {@code health} section of the startup report; it takes no part in the
 * integration itself, and never calls a check. The live panel of the dev console
 * lives in the companion module {@code vidocq-runtime-knock-health-extension-dev}
 * (Vidocq/vidocq#143), which only {@code vidocq:dev} adds.</p>
 *
 * <p>The integration is 100% based on standard SPIs:</p>
 * <ul>
 *   <li>CDI 4.1 BuildCompatibleExtension of {@code io.vidocq.knock.cdi.vauban}
 *       — discover {@code @Liveness/@Readiness/@Startup} beans;</li>
 *   <li>JAX-RS scanning {@code @Path} beans by
 *       {@code vidocq-runtime-cassini-rest-extension} — automatic mount of
 *       {@code KnockHealthResource} (@Path("/health")).</li>
 * </ul>
 *
 * <p>Exposed endpoints (prefixed by {@code vidocq.rest.context-path}):
 * {@code /health}, {@code /health/live}, {@code /health/ready},
 * {@code /health/started}.</p>
 */
module io.vidocq.runtime.extensions.microprofile.knock {
    // Knock modules — transitively re-exposed to consuming applications
    // so that user @Liveness/@Readiness/@Startup beans can
    // implement HealthCheck without manually declaring requires Knock.
    requires transitive io.vidocq.knock.api;
    requires transitive io.vidocq.knock.core;
    requires transitive io.vidocq.knock.cdi.vauban;
    requires transitive io.vidocq.knock.jaxrs;

    // Cassini REST extension: it scans the knock JAX-RS resource (@Path).
    requires io.vidocq.runtime.extensions.jakartaee.core.cassini;

    requires jakarta.cdi;
    requires jakarta.ws.rs;

    // KnockHealthExtension: the health section of the startup report. The devconsole SPI no longer
    // brings io.vidocq.runtime.spi transitively (Vidocq/vidocq#143), so it is required directly.
    requires transitive io.vidocq.runtime.spi;

    // What the health panel of the -dev module reads (Vidocq/vidocq#143); no other module sees it.
    exports io.vidocq.runtime.extensions.microprofile.knock.live to io.vidocq.runtime.extensions.microprofile.knock.dev;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.extensions.microprofile.knock.KnockHealthExtension;
}

