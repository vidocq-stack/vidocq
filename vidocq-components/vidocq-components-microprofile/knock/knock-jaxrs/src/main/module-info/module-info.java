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
 * Knock Jakarta REST endpoints — JAX-RS resources {@code @Path("/health*")} delegating to
 * {@link io.vidocq.knock.spi.HealthCheckRegistry}. Uses only the standard {@code jakarta.ws.rs}
 * API: <strong>no Cassini (or any JAX-RS implementation) dependency</strong>, so it stays
 * portable across runtimes (Cassini, RESTEasy, Jersey…). A Vidocq deployment weaves the Cassini
 * adapter into a repackaged copy at build time (cassini-maven-plugin) — the published jar remains
 * implementation-agnostic.
 *
 * <p>Optional module: a deployment without Jakarta REST can query the registry directly
 * (CLI, tests, other transports).</p>
 *
 * <p><strong>Java Modules note — testCompile workaround</strong>
 * (see {@code docs/adr/ADR-001-java-modules-workaround-microprofile-health.md}) :
 * {@code module-info.java} is in {@code src/main/module-info/} to prevent Maven Compiler
 * Plugin from detecting Java Modules during {@code testCompile} (cassini-core is test-scope,
 * absent from {@code target/javamodules/}).</p>
 */
module io.vidocq.knock.jaxrs {
    requires transitive io.vidocq.knock.core;

    // Jakarta REST API only (implementation provided by the host runtime, e.g. Cassini)
    requires jakarta.ws.rs;
    requires static jakarta.cdi;
    requires static jakarta.inject;
    // Compile-only (optional at runtime): supplies the VaubanComponentProvider service type.
    requires static io.vidocq.vauban.api;
    // Compile-only (optional at runtime): the generated KnockHealthResource$$CassiniAdapter
    // implements a cassini-api type. `requires static` keeps knock-jaxrs runtime-agnostic — the
    // pre-generated adapter stays dormant unless a Cassini runtime is present.
    requires static io.vidocq.cassini.api;

    // In-module instantiation AND field injection of the JAX-RS resource bean (generated as
    // _VaubanComponents): the container creates it with `new KnockHealthResource()` and writes its
    // @Inject field with an in-package `putfield` through this provider, so it needs no reflection
    // and no `opens … to io.vidocq.vauban.core` into this package.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.knock.jaxrs._VaubanComponents;

    // Exposed JAX-RS resource — discovered as a CDI bean (Vauban index) and mounted by the runtime
    exports io.vidocq.knock.jaxrs;

    // No `opens … to io.vidocq.vauban.core`: KnockHealthResource is instantiated and field-injected
    // in-module by the generated _VaubanComponents provider (declared above). Its @Inject field is
    // package-private, so the co-located provider can assign it without deep reflection.
}
