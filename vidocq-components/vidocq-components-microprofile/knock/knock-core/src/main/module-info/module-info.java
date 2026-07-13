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
 * Standalone MicroProfile Health 4.0 implementation — usable in plain SE,
 * without CDI or a container.
 *
 * <p>JSON serialization is produced via Jakarta JSON-P (jakarta.json);
 * champollion is the reference implementation provided at runtime.</p>
 *
 * <p><strong>Java Modules note — Vidocq fork of the MicroProfile Health API</strong>
 * (see {@code docs/adr/ADR-001-java-modules-workaround-microprofile-health.md}) :</p>
 *
 * <ol>
 *   <li><em>Double SPI registration.</em> {@code HealthCheckResponse.named()}
 *   invokes {@code ServiceLoader} through the {@code ContextClassLoader}.
 *   {@code KnockHealthCheckResponseProvider} is therefore registered <em>twice</em>:
 *   {@code provides ... with} here (Java Modules ServiceLoader) and in
 *   {@code META-INF/services/} (ClassLoader ServiceLoader — mandatory because this is the
 *   mechanism used by {@code HealthCheckResponse}).</li>
 *
 *   <li><em>Three-step Maven build.</em> {@code module-info.java} is placed in
 *   {@code src/main/module-info/} (not {@code src/main/java/}) so that Maven Compiler
 *   Plugin does not detect Java Modules during {@code testCompile}. {@code maven-clean-plugin}
 *   purges {@code module-info.class} before {@code testCompile} (incremental builds).
 *   A {@code prepare-package} run recompiles only {@code module-info.java} before
 *   assembling the JAR. Tests run on the classpath ({@code useModulePath=false}).</li>
 * </ol>
 *
 * <p>The fork {@code io.vidocq.knock:knock-mp-health-api} includes a
 * {@code module-info.class} (module {@code microprofile.health.api}), which makes Knock
 * {@code jlink}-compatible without automatic modules.</p>
 */
module io.vidocq.knock.core {
    requires transitive io.vidocq.knock.api;

    // JSON-P for health response serialization (spec §3.1)
    requires jakarta.json;

    // Exported runtime SPI — stable entry point for adapters
    // (knock-cdi-vauban, knock-cassini). The io.vidocq.knock.internal package
    // remains intentionally unexported (project Java Modules boundary).
    exports io.vidocq.knock.runtime;

    // HealthCheckResponseProvider SPI — declared for strict Java Modules environments
    // (the provider is also declared in META-INF/services for the ClassLoader fallback)
    provides org.eclipse.microprofile.health.spi.HealthCheckResponseProvider
            with io.vidocq.knock.internal.KnockHealthCheckResponseProvider;
}
