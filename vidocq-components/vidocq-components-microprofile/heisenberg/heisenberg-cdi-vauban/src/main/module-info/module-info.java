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
 * Heisenberg CDI integration for the Vauban container.
 *
 * <p>Planned components (cf. ROADMAP.md M1+) :</p>
 * <ul>
 *   <li>{@code FaultToleranceInterceptor} — CDI {@code @Interceptor} with priority 4010
 *       (configurable via {@code mp.fault.tolerance.interceptor.priority}).</li>
 *   <li>{@code HeisenbergExtension} — Vauban BCE: validates FT configurations at container startup
 *       (fallback methods, async return types).</li>
 *   <li>{@code StateRegistryBean} — {@code @ApplicationScoped} bean carrying the global state
 *       of CircuitBreaker and Bulkhead (identified by {@code beanClass + method}).</li>
 * </ul>
 *
 * <p><strong>Java Modules note — testCompile workaround</strong>:
 * {@code module-info.java} is in {@code src/main/module-info/} to prevent Maven
 * Compiler Plugin from detecting Java Modules during {@code testCompile} (vauban-core and ravel-core
 * are test-scope, absent from {@code target/javamodules/}).
 * See {@code heisenberg-core/pom.xml} for the complete description of the workaround.</p>
 */
module io.vidocq.heisenberg.cdi.vauban {
    requires transitive io.vidocq.heisenberg.core;
    requires org.eclipse.microprofile.config;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.annotation;
    requires static jakarta.interceptor;
    requires static microprofile.metrics.api;
    requires static io.opentelemetry.api;
    // Compile-only (optional at runtime): supplies the VaubanComponentProvider service type.
    requires static io.vidocq.vauban.api;

    exports io.vidocq.heisenberg.cdi.internal;

    // Heisenberg BCE: validate FT configurations at container startup
    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.heisenberg.cdi.internal.HeisenbergExtension;

    // In-module instantiation and field injection of the FT interceptors and recorder/state beans
    // (@Inject fields are package-private, assigned by an in-package putfield) through the generated
    // _VaubanComponents — so Vauban needs no `opens … to io.vidocq.vauban.core`. The @AroundInvoke
    // methods live in this exported package and are public (invoked without opens).
    provides io.vidocq.vauban.api.VaubanComponentProvider
            with io.vidocq.heisenberg.cdi.internal._VaubanComponents;

    provides org.eclipse.microprofile.config.spi.ConfigProviderResolver
            with io.vidocq.heisenberg.cdi.internal.HeisenbergAutoDiscovery;
}
