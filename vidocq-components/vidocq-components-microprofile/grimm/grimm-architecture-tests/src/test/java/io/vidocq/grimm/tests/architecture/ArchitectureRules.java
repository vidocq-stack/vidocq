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
package io.vidocq.grimm.tests.architecture;

import java.util.Map;

import io.vidocq.archrules.Jdk;
import io.vidocq.archrules.Layer;
import io.vidocq.archrules.Packages;
import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Grimm's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). They come from the shared
 * {@code vidocq-archrules} library of {@code vidocq-parent}, set to Grimm's packages; each names the document it
 * comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the shipped
 * classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 *
 * <p>The packages of each shipped module: {@code io.vidocq.grimm.internal} and {@code io.vidocq.grimm.spi.gen}
 * (grimm-core), {@code io.vidocq.grimm.cdi} (grimm-cdi-vauban) and {@code io.vidocq.grimm.processor}
 * (grimm-processor).
 */
final class ArchitectureRules {

    private static final String MP_OPENAPI = "org.eclipse.microprofile.openapi..";
    private static final String MP_CONFIG = "org.eclipse.microprofile.config..";
    private static final String INTERNAL = "io.vidocq.grimm.internal..";
    private static final String SPI_GEN = "io.vidocq.grimm.spi.gen..";

    /**
     * CLAUDE.md, Architecture constraints 1: grimm-core imports only the MicroProfile OpenAPI and Config APIs,
     * Jakarta REST and Jakarta Annotation. No CDI, no Vauban, no Cassini, no Chappe, no OpenAPI implementation.
     */
    static final ArchRule CORE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {INTERNAL, SPI_GEN},
            Packages.of(Jdk.RUNTIME, MP_OPENAPI, MP_CONFIG, "jakarta.ws.rs..", "jakarta.annotation..", INTERNAL, SPI_GEN),
            "CLAUDE.md, Architecture constraints 1; module-info.java of grimm-core");

    /**
     * Module descriptor of grimm-cdi-vauban: the Jakarta CDI, Inject, Annotation and REST APIs, the MicroProfile
     * OpenAPI and Config APIs, Vauban's API, grimm-core (which exports parts of {@code internal} to it); Cassini
     * only through the dispatch classes cassini-processor generates.
     */
    static final ArchRule CDI_VAUBAN_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.grimm.cdi.."},
            Packages.of(
                    Jdk.RUNTIME,
                    MP_OPENAPI,
                    MP_CONFIG,
                    "jakarta.enterprise..",
                    "jakarta.inject..",
                    "jakarta.annotation..",
                    "jakarta.ws.rs..",
                    "io.vidocq.vauban.api..",
                    "io.vidocq.cassini.spi.gen..",
                    INTERNAL,
                    SPI_GEN,
                    "io.vidocq.grimm.cdi.."),
            "module-info.java of grimm-cdi-vauban; workspace AGENTS.md, zero external dependency");

    /**
     * Module descriptor of grimm-processor: a build-time tool on {@code java.compiler}, reading Jakarta REST, the
     * MicroProfile OpenAPI annotations and grimm-core (it builds the fragment with Grimm's own model objects and
     * schema generator), never shipped in the application.
     */
    static final ArchRule PROCESSOR_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.grimm.processor.."},
            Packages.of(
                    Jdk.COMPILER,
                    MP_OPENAPI,
                    "jakarta.ws.rs..",
                    INTERNAL,
                    SPI_GEN,
                    "io.vidocq.grimm.processor.."),
            "module-info.java of grimm-processor; internals.adoc, Generated code");

    /**
     * CLAUDE.md, Architecture constraints 1, and the module descriptors: grimm-core is the bottom layer and
     * reads no other Grimm module (its generated-contribution contract {@code spi.gen} reads nothing at all);
     * grimm-cdi-vauban and grimm-processor build on it and nothing builds on them.
     */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            "CLAUDE.md, Architecture constraints; the module-info.java files",
            Layer.named("Contract").in(SPI_GEN).accessesNoLayer(),
            Layer.named("Core").in(INTERNAL).accessibleOnlyBy("CDI Vauban", "Processor"),
            Layer.named("CDI Vauban").in("io.vidocq.grimm.cdi..").accessibleByNone(),
            Layer.named("Processor").in("io.vidocq.grimm.processor..").accessibleByNone());

    private static final String SCAN = "internals.adoc, Generated code and reflective fallback: the annotation scan"
            + " that describes a resource class the processor does not generate";

    private static final String BY_NAME = "the MicroProfile OpenAPI specification names the OASFilter and the"
            + " OASModelReader by class name in the configuration, so the class is loaded and instantiated by name";

    /**
     * internals.adoc, "Generated code and reflective fallback", and CLAUDE.md (Architecture constraints 3 and 4):
     * the documented reflective code, each with its reason. Nested classes share the entry of their top-level
     * class.
     */
    static final Map<String, String> REFLECTIVE_FALLBACKS = Map.ofEntries(
            Map.entry("io.vidocq.grimm.internal.scanner.AnnotationScanner", SCAN),
            Map.entry("io.vidocq.grimm.internal.scanner.JaxRsResourceScanner", SCAN),
            Map.entry("io.vidocq.grimm.internal.ModelBuilder", SCAN),
            Map.entry(
                    "io.vidocq.grimm.internal.schema.SchemaGenerator",
                    "the schema of an application type is derived from its fields, methods and annotations; the"
                            + " processor runs the same generator in javac for the resources it handles"),
            Map.entry(
                    "io.vidocq.grimm.internal.schema.BeanValidationMapper",
                    "reads the attributes of a Jakarta or Javax Bean Validation constraint annotation by name, so"
                            + " grimm-core has no compile-time dependency on Bean Validation"),
            Map.entry(
                    "io.vidocq.grimm.internal.serialization.OpenApiValueMapper",
                    "serialises the OpenAPI model by reading the getters of its model beans, in the order the specification"
                            + " lists the document's root fields"),
            Map.entry(
                    "io.vidocq.grimm.internal.ContributionRegistry",
                    "internals.adoc: after the ServiceLoader, loads <resource>$$GrimmModel by name"),
            Map.entry("io.vidocq.grimm.internal.invoker.FilterInvoker", BY_NAME),
            Map.entry("io.vidocq.grimm.internal.invoker.ModelReaderInvoker", BY_NAME),
            Map.entry(
                    "io.vidocq.grimm.cdi.ScannedTypesCreator",
                    "internals.adoc: loads the application classes listed in the processor's service file, for the"
                            + " scan"));

    /**
     * Workspace AGENTS.md, "Maximum static code generation", and internals.adoc: reflection remains only as the
     * documented fallbacks above.
     */
    static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
            REFLECTIVE_FALLBACKS, "workspace AGENTS.md, static code generation; internals.adoc, reflective fallback");

    /** Workspace AGENTS.md, "Virtual Threads everywhere for I/O". Grimm creates no thread. */
    static final ArchRule NO_PLATFORM_THREADS =
            VidocqArchRules.noPlatformThreads(PlatformThreads.NONE, "workspace AGENTS.md, Virtual Threads");

    /** CLAUDE.md, Architecture constraints 2: no {@code ThreadLocal}; {@code ScopedValue} where state is shared. */
    static final ArchRule NO_THREAD_LOCAL = VidocqArchRules.noThreadLocal(
            "a ThreadLocal neither follows work across virtual threads nor caches anything",
            "CLAUDE.md, Architecture constraints 2");

    private ArchitectureRules() {
        // Rules only.
    }
}
