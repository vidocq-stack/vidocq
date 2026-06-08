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
 * Knock CDI integration for the Vauban container — automatic discovery of beans
 * annotated with {@code @Liveness}, {@code @Readiness}, and {@code @Startup} via a
 * Build Compatible Extension, with registration in the {@code HealthCheckRegistry}.
 *
 * <p>Optional module: a standalone SE deployment does not need this module and can feed
 * the registry directly through its programmatic API.</p>
 *
 * <p><strong>JPMS note — testCompile workaround</strong>
 * (see {@code docs/adr/ADR-001-jpms-workaround-microprofile-health.md}) :
 * {@code module-info.java} is in {@code src/main/module-info/} to prevent Maven Compiler
 * Plugin from detecting JPMS during {@code testCompile} (vauban-core is test-scope,
 * absent from {@code target/javamodules/}).</p>
 *
 * <p><strong>Strict JPMS (module-path) requirement.</strong> Vauban's {@code BceProcessor}
 * instantiates Build Compatible Extensions by direct reflection
 * ({@code getDeclaredConstructor().newInstance()}) from module {@code io.vidocq.vauban.core}.
 * Declaring the BCE only via {@code provides ... with} is not enough on the module path: the
 * {@code provides} clause lets the {@code ServiceLoader} instantiate the class, but Vauban does
 * its own reflective instantiation, which requires the hosting package to be open to it. We
 * therefore open {@code io.vidocq.knock.cdi.internal} to {@code io.vidocq.vauban.core} — a
 * <em>qualified</em> open, so the internal package stays unexported (no API leak, honouring the
 * "internal.* not exported" constraint); only Vauban gets deep-reflective access at runtime.
 * Without it, a strict module-path deployment fails at boot with an {@code IllegalAccessException}
 * (e.g. the Vidocq runtime / Arago).</p>
 */
module io.vidocq.knock.cdi.vauban {
    requires transitive io.vidocq.knock.core;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.annotation;
    // Compile-only (optional at runtime): supplies the VaubanComponentProvider service type.
    requires static io.vidocq.vauban.api;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.knock.cdi.internal.HealthCheckCdiExtension;

    // In-module instantiation, field injection AND method invocation of this package's beans
    // (HealthCheckRegistrar, KnockCdiHealthCheckRegistry), generated as _VaubanComponents co-located
    // in io.vidocq.knock.cdi.internal: the container creates them, injects their package-private
    // @Inject fields and fires the @Observes @Initialized observer through this provider — so it
    // needs no deep reflection and no `opens … to io.vidocq.vauban.core`. The BCE itself is loaded
    // via the ServiceLoader `provides` above, not by reflection.
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.knock.cdi.internal._VaubanComponents;
}