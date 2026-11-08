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
package io.vidocq.heisenberg.tests.architecture;

import java.util.Map;

import io.vidocq.archrules.Jdk;
import io.vidocq.archrules.Layer;
import io.vidocq.archrules.Packages;
import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Heisenberg's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). They come from the shared
 * {@code vidocq-archrules} library of {@code vidocq-parent}, set to Heisenberg's packages; each names the document
 * it comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the shipped
 * classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 *
 * <p>The packages of each shipped module: {@code io.vidocq.heisenberg.api} and
 * {@code io.vidocq.heisenberg.spi.gen} (heisenberg-api), {@code io.vidocq.heisenberg.internal} (heisenberg-core),
 * {@code io.vidocq.heisenberg.cdi} (heisenberg-cdi-vauban) and {@code io.vidocq.heisenberg.processor}
 * (heisenberg-processor).
 */
final class ArchitectureRules {

    private static final String MP_FT = "org.eclipse.microprofile.faulttolerance..";

    /** Module descriptor of heisenberg-api: the MicroProfile Fault Tolerance API and the companion contract. */
    static final ArchRule API_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.heisenberg.api..", "io.vidocq.heisenberg.spi.gen.."},
            Packages.of(Jdk.RUNTIME, MP_FT, "io.vidocq.heisenberg.api..", "io.vidocq.heisenberg.spi.gen.."),
            "module-info.java of heisenberg-api; workspace AGENTS.md, zero external dependency");

    /**
     * Module descriptor of heisenberg-core: the Fault Tolerance and Config APIs and heisenberg-api; no CDI (the
     * fallback handler is reached without a compile-time dependency, see {@code REFLECTIVE_FALLBACKS}), no
     * Vauban, no metrics implementation.
     */
    static final ArchRule CORE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.heisenberg.internal.."},
            Packages.of(
                    Jdk.RUNTIME,
                    MP_FT,
                    "org.eclipse.microprofile.config..",
                    "io.vidocq.heisenberg.api..",
                    "io.vidocq.heisenberg.spi.gen..",
                    "io.vidocq.heisenberg.internal.."),
            "module-info.java of heisenberg-core; workspace AGENTS.md, zero external dependency");

    /**
     * Module descriptor of heisenberg-cdi-vauban: the Jakarta CDI, Inject, Interceptor and Annotation APIs, the
     * MicroProfile Fault Tolerance, Config and Metrics APIs, the OpenTelemetry API (all {@code requires static}
     * except Config), Vauban's API and its {@code core.access} lookup ({@code requires static io.vidocq.vauban.core},
     * the private-lookup source of the fallback resolution), heisenberg-core and heisenberg-api. Never another brick's implementation.
     */
    static final ArchRule CDI_VAUBAN_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.heisenberg.cdi.."},
            Packages.of(
                    Jdk.RUNTIME,
                    MP_FT,
                    "org.eclipse.microprofile.config..",
                    "org.eclipse.microprofile.metrics..",
                    "io.opentelemetry.api..",
                    "jakarta.enterprise..",
                    "jakarta.inject..",
                    "jakarta.interceptor..",
                    "jakarta.annotation..",
                    "io.vidocq.vauban.api..",
                    "io.vidocq.vauban.core.access..",
                    "io.vidocq.heisenberg.api..",
                    "io.vidocq.heisenberg.spi.gen..",
                    "io.vidocq.heisenberg.internal..",
                    "io.vidocq.heisenberg.cdi.."),
            "module-info.java of heisenberg-cdi-vauban; workspace AGENTS.md, zero external dependency");

    /** Module descriptor of heisenberg-processor: a build-time tool on {@code java.compiler} alone, never shipped. */
    static final ArchRule PROCESSOR_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {"io.vidocq.heisenberg.processor.."},
            Packages.of(Jdk.COMPILER, "io.vidocq.heisenberg.processor.."),
            "module-info.java of heisenberg-processor; workspace AGENTS.md, zero external dependency");

    /**
     * CLAUDE.md, Architecture constraints, and the module descriptors: heisenberg-api is the bottom layer and reads
     * no other Heisenberg module; heisenberg-core builds on it and exports {@code internal} to heisenberg-cdi-vauban
     * only; nothing builds on the adapter or the processor.
     */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            "CLAUDE.md, Architecture constraints; the module-info.java files",
            Layer.named("API").in("io.vidocq.heisenberg.api..", "io.vidocq.heisenberg.spi.gen..").accessesNoLayer(),
            Layer.named("Core").in("io.vidocq.heisenberg.internal..").accessibleOnlyBy("CDI Vauban"),
            Layer.named("CDI Vauban").in("io.vidocq.heisenberg.cdi..").accessibleByNone(),
            Layer.named("Processor").in("io.vidocq.heisenberg.processor..").accessibleByNone());

    private static final String METHOD_IDENTITY = "internals.adoc: the guarded java.lang.reflect.Method is carried"
            + " as the identity of the method (policy key, metric label), as the Fault Tolerance API's"
            + " interceptor hands it; it is never invoked or made accessible here";

    /**
     * internals.adoc, "Generated code and reflective fallback", and CLAUDE.md (constraint 4): the documented
     * reflective code, each with its reason. Nested classes share the entry of their top-level class.
     */
    static final Map<String, String> REFLECTIVE_FALLBACKS = Map.ofEntries(
            Map.entry(
                    "io.vidocq.heisenberg.internal.AnnotationReader",
                    "internals.adoc: reads the Fault Tolerance annotations by reflection when the class has no"
                            + " generated companion, and logs it once per class"),
            Map.entry(
                    "io.vidocq.heisenberg.internal.Companions",
                    "internals.adoc: finds the generated <Bean>$$HeisenbergPolicies companion by name, after the"
                            + " ServiceLoader of the bean's module layer"),
            Map.entry(
                    "io.vidocq.heisenberg.internal.ConfigResolver",
                    "internals.adoc: applies the Config property overrides to the annotations present on the guarded method and"
                            + " class, and loads by name the exception classes the properties name"),
            Map.entry(
                    "io.vidocq.heisenberg.internal.FallbackResolver",
                    "internals.adoc, fallback lookup: resolves the fallbackMethod of @Fallback, the handler class and,"
                            + " to leave heisenberg-core without a CDI dependency, the handler bean through"
                            + " CDI.current() looked up by name"),
            Map.entry(
                    "io.vidocq.heisenberg.cdi.internal.HeisenbergExtension",
                    "internals.adoc: the extension that loads each guarded class and walks its declared methods to"
                            + " validate the definitions"),
            Map.entry(
                    "io.vidocq.heisenberg.cdi.internal.FaultToleranceInterceptor",
                    "internals.adoc: resolves the guarded method of the intercepted bean, looking the declared method"
                            + " up on the bean class"),
            Map.entry(
                    "io.vidocq.heisenberg.internal.RetryEngine",
                    "unwraps an InvocationTargetException failure to its cause; the exception is the only java.lang.reflect"
                            + " type it names"),
            Map.entry(
                    "io.vidocq.heisenberg.internal.Failures",
                    "wraps a checked failure in an UndeclaredThrowableException, as its Javadoc says; the only"
                            + " java.lang.reflect type it names"),
            Map.entry("io.vidocq.heisenberg.api.FtMetricsRecorder", METHOD_IDENTITY),
            Map.entry("io.vidocq.heisenberg.cdi.internal.CompositeFtMetricsRecorder", METHOD_IDENTITY),
            Map.entry(
                    "io.vidocq.heisenberg.cdi.internal.DelegatingFtMetricsRecorder",
                    METHOD_IDENTITY + "; it also loads the metrics recorder class by name, so the Dirac and"
                            + " OpenTelemetry APIs stay optional"),
            Map.entry("io.vidocq.heisenberg.cdi.internal.DiracFtMetrics", METHOD_IDENTITY),
            Map.entry("io.vidocq.heisenberg.cdi.internal.OtelFtMetrics", METHOD_IDENTITY),
            Map.entry("io.vidocq.heisenberg.cdi.internal.DiracFtMetricsRecorder", METHOD_IDENTITY),
            Map.entry(
                    "io.vidocq.heisenberg.cdi.internal.DiracFtMetricsRecorder_ClientProxy",
                    "the client proxy Vauban generates for DiracFtMetricsRecorder: " + METHOD_IDENTITY),
            Map.entry("io.vidocq.heisenberg.cdi.internal.OtelFtMetricsRecorder", METHOD_IDENTITY),
            Map.entry(
                    "io.vidocq.heisenberg.cdi.internal.OtelFtMetricsRecorder_ClientProxy",
                    "the client proxy Vauban generates for OtelFtMetricsRecorder: " + METHOD_IDENTITY),
            Map.entry("io.vidocq.heisenberg.internal.BulkheadEngine", METHOD_IDENTITY),
            Map.entry("io.vidocq.heisenberg.internal.FallbackPolicy", METHOD_IDENTITY),
            Map.entry("io.vidocq.heisenberg.internal.MethodPolicies", METHOD_IDENTITY),
            Map.entry("io.vidocq.heisenberg.internal.PolicyComposer", METHOD_IDENTITY));

    /**
     * Workspace AGENTS.md, "Maximum static code generation", and internals.adoc: reflection remains only as the
     * documented fallbacks above.
     */
    static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
            REFLECTIVE_FALLBACKS, "workspace AGENTS.md, static code generation; internals.adoc, reflective fallback");

    /**
     * Workspace AGENTS.md, "Virtual Threads everywhere for I/O", and CLAUDE.md: {@code @Timeout} and
     * {@code @Asynchronous} run on {@code Thread.ofVirtual()}, the bulkhead on a {@code Semaphore}.
     */
    static final ArchRule NO_PLATFORM_THREADS = VidocqArchRules.noPlatformThreads(
            PlatformThreads.NONE, "workspace AGENTS.md, Virtual Threads; CLAUDE.md, Standards");

    /**
     * CLAUDE.md, Architecture constraints 2: no {@code ThreadLocal}. ({@code ThreadLocalRandom}, the retry jitter,
     * is not a {@code ThreadLocal}.)
     */
    static final ArchRule NO_THREAD_LOCAL = VidocqArchRules.noThreadLocal(
            "a ThreadLocal neither follows work across virtual threads nor caches anything",
            "CLAUDE.md, Architecture constraints 2");

    private ArchitectureRules() {
        // Rules only.
    }
}
