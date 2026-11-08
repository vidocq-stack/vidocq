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
package io.vidocq.dirac.tests.architecture;

import java.util.Map;

import io.vidocq.archrules.Jdk;
import io.vidocq.archrules.Layer;
import io.vidocq.archrules.Packages;
import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Dirac's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). They come from the shared
 * {@code vidocq-archrules} library of {@code vidocq-parent}, set to Dirac's packages; each names the document it
 * comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the shipped
 * classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 *
 * <p>The packages of each shipped module: {@code io.vidocq.dirac.api} and {@code io.vidocq.dirac.spi.gen}
 * (dirac-api), {@code io.vidocq.dirac.internal} (dirac-core), {@code io.vidocq.dirac.cdi} (dirac-cdi-vauban),
 * {@code io.vidocq.dirac.rest} (dirac-rest) and {@code io.vidocq.dirac.processor} (dirac-processor).
 */
final class ArchitectureRules {

    private static final String MP_METRICS = "org.eclipse.microprofile.metrics..";

    /**
     * Module descriptor of dirac-api: it re-exports the MicroProfile Metrics API (which itself reads the
     * Interceptor API) and holds the generated-companion contract, nothing else.
     */
    static final ArchRule API_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.dirac.api..", "io.vidocq.dirac.spi.gen.."},
            Packages.of(
                    Jdk.RUNTIME, MP_METRICS, "jakarta.interceptor..", "io.vidocq.dirac.api..", "io.vidocq.dirac.spi.gen.."),
            "module-info.java of dirac-api; workspace AGENTS.md, zero external dependency");

    /**
     * CLAUDE.md, Architecture constraints 1: dirac-core imports no implementation library, only the MicroProfile
     * Metrics API (and {@code java.management}, which its module descriptor requires); no CDI, no REST.
     */
    static final ArchRule CORE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.dirac.internal.."},
            Packages.of(
                    Jdk.RUNTIME,
                    "javax.management..",
                    MP_METRICS,
                    "io.vidocq.dirac.api..",
                    "io.vidocq.dirac.spi.gen..",
                    "io.vidocq.dirac.internal.."),
            "CLAUDE.md, Architecture constraints 1; module-info.java of dirac-core");

    /**
     * Module descriptor of dirac-cdi-vauban: the Jakarta CDI, Inject, Interceptor and Annotation APIs, Vauban's
     * API, dirac-core (which exports {@code internal} to it) and dirac-api.
     */
    static final ArchRule CDI_VAUBAN_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.dirac.cdi.."},
            Packages.of(
                    Jdk.RUNTIME,
                    MP_METRICS,
                    "jakarta.enterprise..",
                    "jakarta.inject..",
                    "jakarta.interceptor..",
                    "jakarta.annotation..",
                    "io.vidocq.vauban.api..",
                    "io.vidocq.dirac.api..",
                    "io.vidocq.dirac.spi.gen..",
                    "io.vidocq.dirac.internal..",
                    "io.vidocq.dirac.cdi.."),
            "module-info.java of dirac-cdi-vauban; workspace AGENTS.md, zero external dependency");

    /**
     * Module descriptor of dirac-rest: Jakarta REST, CDI and Inject, Vauban's API, dirac-core; Cassini only
     * through the dispatch classes cassini-processor generates ({@code io.vidocq.cassini.spi.gen}), its own code
     * being a plain JAX-RS resource.
     */
    static final ArchRule REST_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.dirac.rest.."},
            Packages.of(
                    Jdk.RUNTIME,
                    MP_METRICS,
                    "jakarta.ws.rs..",
                    "jakarta.enterprise..",
                    "jakarta.inject..",
                    "io.vidocq.vauban.api..",
                    "io.vidocq.cassini.spi.gen..",
                    "io.vidocq.dirac.api..",
                    "io.vidocq.dirac.internal..",
                    "io.vidocq.dirac.rest.."),
            "module-info.java of dirac-rest; workspace AGENTS.md, zero external dependency");

    /**
     * Module descriptor of dirac-processor: a build-time tool on {@code java.compiler}, reading the MicroProfile
     * Metrics annotations and dirac-api's companion contract, never shipped in the application.
     */
    static final ArchRule PROCESSOR_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.dirac.processor.."},
            Packages.of(
                    Jdk.COMPILER,
                    MP_METRICS,
                    "io.vidocq.dirac.api..",
                    "io.vidocq.dirac.spi.gen..",
                    "io.vidocq.dirac.processor.."),
            "module-info.java of dirac-processor; workspace AGENTS.md, zero external dependency");

    /**
     * CLAUDE.md, Architecture constraints 1, and the module descriptors: dirac-api is the bottom layer and reads
     * no other Dirac module; dirac-core builds on it and exports {@code internal} to the CDI and REST adapters
     * only; nothing builds on the adapters or on the processor.
     */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            "CLAUDE.md, Architecture constraints; the module-info.java files",
            Layer.named("API").in("io.vidocq.dirac.api..", "io.vidocq.dirac.spi.gen..").accessesNoLayer(),
            Layer.named("Core").in("io.vidocq.dirac.internal..").accessibleOnlyBy("CDI Vauban", "REST"),
            Layer.named("CDI Vauban").in("io.vidocq.dirac.cdi..").accessibleByNone(),
            Layer.named("REST").in("io.vidocq.dirac.rest..").accessibleByNone(),
            Layer.named("Processor").in("io.vidocq.dirac.processor..").accessibleByNone());

    /**
     * internals.adoc, "Generated code and reflective fallback", and CLAUDE.md (constraints 3 and 4): the
     * documented reflective code of dirac-cdi-vauban, each with its reason. Nested classes share the entry of
     * their top-level class.
     */
    static final Map<String, String> REFLECTIVE_FALLBACKS = Map.of(
            "io.vidocq.dirac.cdi.internal.CompanionRegistry",
            "internals.adoc: loads the generated <Bean>$$DiracMetrics companion by name when no ServiceLoader entry"
                    + " names it",
            "io.vidocq.dirac.cdi.internal.DiscoveredMetrics",
            "internals.adoc: the fallback that scans the annotations of a class by reflection when no generated"
                    + " companion exists",
            "io.vidocq.dirac.cdi.internal.DiscoveredMetricsCreator",
            "internals.adoc: loads, at container start, the classes the extension found",
            "io.vidocq.dirac.cdi.internal.DiracExtension",
            "internals.adoc: the extension that runs at boot when the processor did not run; it reads the metric"
                    + " annotations of the discovered classes",
            "io.vidocq.dirac.cdi.internal.CountedInterceptor",
            "internals.adoc, Counted interceptor: reads @Counted on the invoked method, resolved once per bean class"
                    + " and method and cached",
            "io.vidocq.dirac.cdi.internal.TimedInterceptor",
            "internals.adoc, Counted interceptor: the same scheme for @Timed",
            "io.vidocq.dirac.cdi.internal.MetricRegistryProducerBean",
            "internals.adoc: reads the @Metric and @RegistryScope qualifiers of the injection point");

    /**
     * Workspace AGENTS.md, "Maximum static code generation", and internals.adoc: reflection remains only as the
     * documented fallbacks above.
     */
    static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
            REFLECTIVE_FALLBACKS, "workspace AGENTS.md, static code generation; internals.adoc, reflective fallback");

    /**
     * Workspace AGENTS.md, "Virtual Threads everywhere for I/O". Dirac has no I/O thread of its own: it creates
     * none.
     */
    static final ArchRule NO_PLATFORM_THREADS =
            VidocqArchRules.noPlatformThreads(PlatformThreads.NONE, "workspace AGENTS.md, Virtual Threads");

    /** CLAUDE.md, Architecture constraints 2, and AGENTS.md: no {@code ThreadLocal}; {@code ScopedValue} instead. */
    static final ArchRule NO_THREAD_LOCAL = VidocqArchRules.noThreadLocal(
            "a ThreadLocal neither follows work across virtual threads nor caches anything",
            "CLAUDE.md, Architecture constraints 2; AGENTS.md");

    private ArchitectureRules() {
        // Rules only.
    }
}
