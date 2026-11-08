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
package io.vidocq.ravel.tests.architecture;

import java.util.Map;

import io.vidocq.archrules.Jdk;
import io.vidocq.archrules.Layer;
import io.vidocq.archrules.Packages;
import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Ravel's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). They come from the shared
 * {@code vidocq-archrules} library of {@code vidocq-parent}, set to Ravel's packages; each names the document it
 * comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the shipped
 * classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 *
 * <p>The root package of each shipped module: {@code io.vidocq.ravel.spi} (ravel-api),
 * {@code io.vidocq.ravel.internal} (ravel-core), {@code io.vidocq.ravel.cdi} (ravel-cdi-vauban) and
 * {@code io.vidocq.ravel.processor} (ravel-processor).
 */
final class ArchitectureRules {

    private static final String MP_CONFIG = "org.eclipse.microprofile.config..";

    /** AGENTS.md, Boundaries: ravel-api re-exports the specification and reads nothing else. */
    static final ArchRule API_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.ravel.spi.."},
            Packages.of(Jdk.RUNTIME, MP_CONFIG, "io.vidocq.ravel.spi.."),
            "AGENTS.md, Boundaries; module-info.java of ravel-api");

    /**
     * CLAUDE.md, Architecture constraints 1: ravel-core only depends on the MicroProfile Config API (and the SPI
     * of ravel-api), no CDI, no Servlet, no other Jakarta specification.
     */
    static final ArchRule CORE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.ravel.internal.."},
            Packages.of(Jdk.RUNTIME, MP_CONFIG, "io.vidocq.ravel.spi..", "io.vidocq.ravel.internal.."),
            "CLAUDE.md, Architecture constraints 1; module-info.java of ravel-core");

    /**
     * CLAUDE.md, Architecture constraints 2, and the module descriptor of ravel-cdi-vauban: the Jakarta CDI,
     * Inject and Annotation APIs, the MicroProfile Config API, the SPI of ravel-api and Vauban's API (the
     * adapter's {@code requires static io.vidocq.vauban.api}). Never the internals of ravel-core, never
     * Vauban's implementation.
     */
    static final ArchRule CDI_VAUBAN_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.ravel.cdi.."},
            Packages.of(
                    Jdk.RUNTIME,
                    MP_CONFIG,
                    "jakarta.enterprise..",
                    "jakarta.inject..",
                    "jakarta.annotation..",
                    "io.vidocq.vauban.api..",
                    "io.vidocq.ravel.spi..",
                    "io.vidocq.ravel.cdi.."),
            "CLAUDE.md, Architecture constraints 2; module-info.java of ravel-cdi-vauban");

    /** Module descriptor of ravel-processor: a build-time tool on {@code java.compiler} alone, never shipped. */
    static final ArchRule PROCESSOR_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.ravel.processor.."},
            Packages.of(Jdk.COMPILER, "io.vidocq.ravel.processor.."),
            "module-info.java of ravel-processor; workspace AGENTS.md, zero external dependency");

    /**
     * CLAUDE.md, Architecture constraints 2, and the module descriptors: ravel-api is the bottom layer and
     * reads no other Ravel module; ravel-core builds on it and nothing but its own tests reaches ravel-core's
     * internals ({@code internal.*} is unexported); ravel-cdi-vauban builds on the SPI only, never the reverse;
     * ravel-processor stands alone.
     */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            "CLAUDE.md, Architecture constraints; the module-info.java files",
            Layer.named("SPI").in("io.vidocq.ravel.spi..").accessesNoLayer(),
            Layer.named("Core").in("io.vidocq.ravel.internal..").accessibleByNone(),
            Layer.named("CDI Vauban").in("io.vidocq.ravel.cdi..").accessibleByNone(),
            Layer.named("Processor")
                    .in("io.vidocq.ravel.processor..")
                    .accessesNoLayer()
                    .accessibleByNone());

    /**
     * internals.adoc, "Generated code and reflective fallback", AGENTS.md and CLAUDE.md (constraint 5): the
     * documented reflective code, each with its reason. Nested classes share the entry of their top-level class.
     */
    static final Map<String, String> REFLECTIVE_FALLBACKS = Map.of(
            "io.vidocq.ravel.internal.ImplicitConverter",
            "the implicit converter of the specification (5.2), found and called on the type's public members when"
                    + " no generated converter exists; never setAccessible (CLAUDE.md, constraint 5)",
            "io.vidocq.ravel.internal.PrioritizedConverter",
            "reads @Priority by qualified name, so ravel-core needs no compile-time dependency on jakarta.annotation"
                    + " (AGENTS.md, Boundaries)",
            "io.vidocq.ravel.internal.ArrayConverter",
            "creates an array of a component type known at run time only (specification 5.4)",
            "io.vidocq.ravel.internal.BuiltInConverters",
            "the Class<?> built-in converter loads the class a configuration value names (specification 5.1)",
            "io.vidocq.ravel.internal.RavelConfigBuilder",
            "reads the target type of a registered Converter<T> from its generic interface (specification 5.3)",
            "io.vidocq.ravel.cdi.internal.ConfigPropertiesSyntheticCreator",
            "internals.adoc: the reflective path that fills a @ConfigProperties class when the generated code is"
                    + " missing",
            "io.vidocq.ravel.cdi.internal.RavelConfigPropertyResolver",
            "internals.adoc: the reflective path that resolves a @ConfigProperty injection point's type",
            "io.vidocq.ravel.cdi.ConfigCdiExtension",
            "internals.adoc: the extension that runs at boot when the processor did not run: it reads the"
                    + " @ConfigProperties fields reflectively for the deployment validation",
            "io.vidocq.ravel.cdi.ConfigPropertiesExclusionExtension",
            "internals.adoc: the same boot-time extension path, reflective on the @ConfigProperties class");

    /**
     * Workspace AGENTS.md, "Maximum static code generation", and internals.adoc: reflection remains only as the
     * documented fallbacks above.
     */
    static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
            REFLECTIVE_FALLBACKS, "workspace AGENTS.md, static code generation; internals.adoc, reflective fallback");

    /**
     * Workspace AGENTS.md, "Virtual Threads everywhere for I/O". Ravel has no I/O thread of its own: it creates
     * none.
     */
    static final ArchRule NO_PLATFORM_THREADS =
            VidocqArchRules.noPlatformThreads(PlatformThreads.NONE, "workspace AGENTS.md, Virtual Threads");

    /**
     * AGENTS.md, Boundaries, and CLAUDE.md (constraint 4): no {@code ThreadLocal}; a propagated context, such as
     * the expression stack, is a {@code ScopedValue}.
     */
    static final ArchRule NO_THREAD_LOCAL = VidocqArchRules.noThreadLocal(
            "a ThreadLocal neither follows work across virtual threads nor caches anything; a propagated context is a"
                    + " ScopedValue",
            "AGENTS.md, Boundaries; CLAUDE.md, Architecture constraints 4");

    private ArchitectureRules() {
        // Rules only.
    }
}
