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
package io.vidocq.cervantes.tests.architecture;

import java.util.Map;

import io.vidocq.archrules.Jdk;
import io.vidocq.archrules.Layer;
import io.vidocq.archrules.Packages;
import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Cervantes's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). They come from the shared
 * {@code vidocq-archrules} library of {@code vidocq-parent}, set to Cervantes's packages; each names the document
 * it comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the shipped
 * classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 *
 * <p>The root package of each shipped module: {@code io.vidocq.cervantes.api} (cervantes-api),
 * {@code io.vidocq.cervantes.internal} (cervantes-core), {@code io.vidocq.cervantes.cdi} (cervantes-cdi-vauban)
 * and {@code io.vidocq.cervantes.jaxrs} (cervantes-jaxrs).
 */
final class ArchitectureRules {

    private static final String MP_JWT = "org.eclipse.microprofile.jwt..";

    /** Module descriptor of cervantes-api: it re-exports the MicroProfile JWT API and reads nothing else. */
    static final ArchRule API_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.cervantes.api.."},
            Packages.of(Jdk.RUNTIME, MP_JWT, "io.vidocq.cervantes.api.."),
            "module-info.java of cervantes-api; workspace AGENTS.md, zero external dependency");

    /**
     * CLAUDE.md, Architecture constraints 2: cervantes-core depends on neither Cassini nor CDI. It reads
     * Jakarta JSON-P (the JWT claims; Champollion is the implementation, brought by the extension), the JDK for
     * the signatures, encryption (JWE) and the JWKS fetch, and cervantes-api.
     */
    static final ArchRule CORE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.cervantes.internal.."},
            Packages.of(
                    Jdk.RUNTIME,
                    MP_JWT,
                    "jakarta.json..",
                    "io.vidocq.cervantes.api..",
                    "io.vidocq.cervantes.internal.."),
            "CLAUDE.md, Architecture constraints 2; module-info.java of cervantes-core");

    /**
     * Module descriptor of cervantes-cdi-vauban: the Jakarta CDI, Inject, Annotation and JSON-P APIs, the
     * MicroProfile Config and JWT APIs, Vauban's API, cervantes-api and cervantes-core (which exports
     * {@code internal} to it).
     */
    static final ArchRule CDI_VAUBAN_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.cervantes.cdi.."},
            Packages.of(
                    Jdk.RUNTIME,
                    MP_JWT,
                    "org.eclipse.microprofile.config..",
                    "jakarta.enterprise..",
                    "jakarta.inject..",
                    "jakarta.annotation..",
                    "jakarta.json..",
                    "io.vidocq.vauban.api..",
                    "io.vidocq.cervantes.api..",
                    "io.vidocq.cervantes.internal..",
                    "io.vidocq.cervantes.cdi.."),
            "module-info.java of cervantes-cdi-vauban; workspace AGENTS.md, zero external dependency");

    /**
     * Module descriptor of cervantes-jaxrs: Jakarta REST, CDI, Inject and Annotation (the security annotations),
     * the MicroProfile Config and JWT APIs, Vauban's API, cervantes-api and cervantes-cdi-vauban; Cassini only
     * through the dispatch classes cassini-processor generates ({@code io.vidocq.cassini.spi.gen}).
     */
    static final ArchRule JAXRS_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.cervantes.jaxrs.."},
            Packages.of(
                    Jdk.RUNTIME,
                    MP_JWT,
                    "org.eclipse.microprofile.config..",
                    "jakarta.ws.rs..",
                    "jakarta.enterprise..",
                    "jakarta.inject..",
                    "jakarta.annotation..",
                    "io.vidocq.vauban.api..",
                    "io.vidocq.cassini.spi.gen..",
                    "io.vidocq.cervantes.api..",
                    "io.vidocq.cervantes.cdi..",
                    "io.vidocq.cervantes.jaxrs.."),
            "module-info.java of cervantes-jaxrs; workspace AGENTS.md, zero external dependency");

    /**
     * CLAUDE.md, Architecture constraints 2, and the module descriptors: cervantes-api is the bottom layer and
     * reads no other Cervantes module; cervantes-core builds on it and exports {@code internal} to
     * cervantes-cdi-vauban only; cervantes-jaxrs builds on the CDI adapter; nothing builds on cervantes-jaxrs.
     */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            "CLAUDE.md, Architecture constraints; the module-info.java files",
            Layer.named("API").in("io.vidocq.cervantes.api..").accessesNoLayer(),
            Layer.named("Core").in("io.vidocq.cervantes.internal..").accessibleOnlyBy("CDI Vauban"),
            Layer.named("CDI Vauban").in("io.vidocq.cervantes.cdi..").accessibleOnlyBy("JAX-RS"),
            Layer.named("JAX-RS").in("io.vidocq.cervantes.jaxrs..").accessibleByNone());

    /**
     * internals.adoc, "Generated code and reflective fallback" and "RolesAllowedDynamicFeature", and CLAUDE.md
     * (constraint 4): the documented reflective code, each with its reason. Nested classes share the entry of their
     * top-level class.
     */
    static final Map<String, String> REFLECTIVE_FALLBACKS = Map.of(
            "io.vidocq.cervantes.cdi.CervantesClaimExtension",
            "the extension that runs at boot: it loads by name the classes of the build-time type model of the"
                    + " @Claim injection points, and creates the array class of an array claim",
            "io.vidocq.cervantes.cdi.internal.ClaimResolver",
            "reads the generic type of a @Claim injection point (Optional, Set, List...) to convert the claim",
            "io.vidocq.cervantes.jaxrs.RolesAllowedDynamicFeature",
            "internals.adoc: introspects the JAX-RS resource method and class once at startup for @RolesAllowed,"
                    + " @PermitAll and @DenyAll, and registers the filter; the decision is static per endpoint");

    /**
     * Workspace AGENTS.md, "Maximum static code generation", and internals.adoc: reflection remains only as the
     * documented fallbacks above.
     */
    static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
            REFLECTIVE_FALLBACKS, "workspace AGENTS.md, static code generation; internals.adoc, reflective fallback");

    /**
     * Workspace AGENTS.md, "Virtual Threads everywhere for I/O", and CLAUDE.md (constraint 3): the JWKS fetch uses
     * the JDK {@code HttpClient}, no thread of its own.
     */
    static final ArchRule NO_PLATFORM_THREADS = VidocqArchRules.noPlatformThreads(
            PlatformThreads.NONE, "workspace AGENTS.md, Virtual Threads; CLAUDE.md, Architecture constraints 3");

    /**
     * CLAUDE.md, Architecture constraints 3: no {@code ThreadLocal} (pinning risk); the JAX-RS
     * {@code SecurityContext} is replaced instead.
     */
    static final ArchRule NO_THREAD_LOCAL = VidocqArchRules.noThreadLocal(
            "a ThreadLocal neither follows a request across virtual threads nor caches anything",
            "CLAUDE.md, Architecture constraints 3");

    private ArchitectureRules() {
        // Rules only.
    }
}
