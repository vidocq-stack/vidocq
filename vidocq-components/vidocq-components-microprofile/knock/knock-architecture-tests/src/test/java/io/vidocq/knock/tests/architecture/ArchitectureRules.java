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
package io.vidocq.knock.tests.architecture;

import java.util.Map;

import io.vidocq.archrules.Jdk;
import io.vidocq.archrules.Layer;
import io.vidocq.archrules.Packages;
import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Knock's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). They come from the shared
 * {@code vidocq-archrules} library of {@code vidocq-parent}, set to Knock's packages; each names the document it
 * comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the shipped
 * classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 *
 * <p>The root package of each shipped module: {@code io.vidocq.knock.spi} (knock-api),
 * {@code io.vidocq.knock.runtime} and {@code io.vidocq.knock.internal} (knock-core), {@code io.vidocq.knock.cdi}
 * (knock-cdi-vauban) and {@code io.vidocq.knock.jaxrs} (knock-jaxrs).
 */
final class ArchitectureRules {

    private static final String MP_HEALTH = "org.eclipse.microprofile.health..";

    /** CLAUDE.md, Architecture: knock-api holds the SPI and reads the MicroProfile Health API, nothing else. */
    static final ArchRule API_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.knock.spi.."},
            Packages.of(Jdk.RUNTIME, MP_HEALTH, "io.vidocq.knock.spi.."),
            "CLAUDE.md, Architecture; module-info.java of knock-api");

    /**
     * CLAUDE.md, Architecture constraints 1 and 4: knock-core depends on the MicroProfile Health API and the
     * Jakarta JSON-P API only (champollion is provided at run time, never another JSON library); no CDI, no REST.
     */
    static final ArchRule CORE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.knock.runtime..", "io.vidocq.knock.internal.."},
            Packages.of(
                    Jdk.RUNTIME,
                    MP_HEALTH,
                    "jakarta.json..",
                    "io.vidocq.knock.spi..",
                    "io.vidocq.knock.runtime..",
                    "io.vidocq.knock.internal.."),
            "CLAUDE.md, Architecture constraints 1 and 4; module-info.java of knock-core");

    /**
     * CLAUDE.md, Architecture constraints 2: knock-cdi-vauban depends on knock-core and the Jakarta CDI APIs; its
     * module descriptor also reads Vauban's API.
     */
    static final ArchRule CDI_VAUBAN_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.knock.cdi.."},
            Packages.of(
                    Jdk.RUNTIME,
                    MP_HEALTH,
                    "jakarta.enterprise..",
                    "jakarta.inject..",
                    "jakarta.annotation..",
                    "io.vidocq.vauban.api..",
                    "io.vidocq.knock.spi..",
                    "io.vidocq.knock.runtime..",
                    "io.vidocq.knock.cdi.."),
            "CLAUDE.md, Architecture constraints 2; module-info.java of knock-cdi-vauban");

    /**
     * CLAUDE.md, Architecture constraints 3: knock-jaxrs depends on knock-core and Jakarta REST, and on no Cassini
     * class in its own code (its resource is a standard JAX-RS resource, "no internal Cassini class imports"). The
     * only Cassini types are those of {@code io.vidocq.cassini.spi.gen}, the generated dispatch classes
     * {@code KnockHealthResource$$CassiniAdapter} and {@code $$CassiniRoutes} that cassini-processor writes at
     * Knock's build (internals.adoc, "Generated code and reflective fallback").
     */
    static final ArchRule JAXRS_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.knock.jaxrs.."},
            Packages.of(
                    Jdk.RUNTIME,
                    MP_HEALTH,
                    "jakarta.ws.rs..",
                    "jakarta.enterprise..",
                    "jakarta.inject..",
                    "io.vidocq.vauban.api..",
                    "io.vidocq.cassini.spi.gen..",
                    "io.vidocq.knock.spi..",
                    "io.vidocq.knock.runtime..",
                    "io.vidocq.knock.jaxrs.."),
            "CLAUDE.md, Architecture constraints 3; module-info.java of knock-jaxrs");

    /**
     * CLAUDE.md, Architecture constraints 2, 3 and 5, and the module descriptors: knock-api is the bottom layer
     * and reads no other Knock module; knock-core exports {@code runtime} and keeps {@code internal} to itself; the
     * CDI and REST adapters build on the runtime and the SPI, never on the internals, and nothing builds on them.
     */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            "CLAUDE.md, Architecture constraints; the module-info.java files",
            Layer.named("SPI").in("io.vidocq.knock.spi..").accessesNoLayer(),
            Layer.named("Runtime").in("io.vidocq.knock.runtime..").accessibleOnlyBy("CDI Vauban", "JAX-RS"),
            Layer.named("Internals").in("io.vidocq.knock.internal..").accessibleOnlyBy("Runtime"),
            Layer.named("CDI Vauban").in("io.vidocq.knock.cdi..").accessibleByNone(),
            Layer.named("JAX-RS").in("io.vidocq.knock.jaxrs..").accessibleByNone());

    /**
     * internals.adoc, "Generated code and reflective fallback": "Knock itself has no reflective path", and
     * CLAUDE.md, constraint 7. No documented fallback.
     */
    static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
            Map.of(), "workspace AGENTS.md, static code generation; internals.adoc, reflective fallback");

    /**
     * Workspace AGENTS.md, "Virtual Threads everywhere for I/O", and internals.adoc ("no platform-thread pool"):
     * the aggregator runs the checks on {@code newVirtualThreadPerTaskExecutor()}.
     */
    static final ArchRule NO_PLATFORM_THREADS = VidocqArchRules.noPlatformThreads(
            PlatformThreads.NONE, "workspace AGENTS.md, Virtual Threads; internals.adoc");

    /** CLAUDE.md, Architecture constraints 6: no {@code ThreadLocal}; {@code ScopedValue} for a propagated context. */
    static final ArchRule NO_THREAD_LOCAL = VidocqArchRules.noThreadLocal(
            "a ThreadLocal neither follows work across virtual threads nor caches anything",
            "CLAUDE.md, Architecture constraints 6");

    private ArchitectureRules() {
        // Rules only.
    }
}
