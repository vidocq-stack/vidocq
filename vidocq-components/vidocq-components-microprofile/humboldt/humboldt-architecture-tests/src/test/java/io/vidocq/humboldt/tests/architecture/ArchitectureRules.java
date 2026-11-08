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
package io.vidocq.humboldt.tests.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.Map;

import io.vidocq.archrules.Jdk;
import io.vidocq.archrules.Layer;
import io.vidocq.archrules.Packages;
import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Humboldt's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). They come from the shared
 * {@code vidocq-archrules} library of {@code vidocq-parent}, set to Humboldt's packages; each names the document it
 * comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the shipped
 * classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 *
 * <p>The classes of the three repackages of the upstream OpenTelemetry API (humboldt-otel-api,
 * humboldt-otel-context, humboldt-otel-instrumentation-annotations) are not Humboldt code and are not imported.
 */
final class ArchitectureRules {

    private static final String OTEL_API = "io.opentelemetry.api..";
    private static final String OTEL_CONTEXT = "io.opentelemetry.context..";
    private static final String HUMBOLDT_API = "io.vidocq.humboldt";
    private static final String HUMBOLDT_SPI = "io.vidocq.humboldt.spi..";
    private static final String COMMON = "io.vidocq.humboldt.sdk.common..";
    private static final String TRACE = "io.vidocq.humboldt.sdk.trace..";
    private static final String METRIC = "io.vidocq.humboldt.sdk.metric..";
    private static final String LOG = "io.vidocq.humboldt.sdk.log..";
    private static final String W3C = "io.vidocq.humboldt.propagator.w3c..";
    private static final String EXPORTER = "io.vidocq.humboldt.exporter.otlp.http..";
    private static final String HUMBOLDT_CONTEXT = "io.vidocq.humboldt.context..";
    private static final String RUNTIME = "io.vidocq.humboldt.runtime..";
    private static final String CDI = "io.vidocq.humboldt.cdi..";
    private static final String REST = "io.vidocq.humboldt.rest..";
    private static final String INTEROP = "io.vidocq.humboldt.otel.interop..";

    private static final String DESIGN_SOURCE =
            "CLAUDE.md, Architecture and Architectural constraints; the module-info.java files";

    /** CLAUDE.md, Architecture: humboldt-api is the public facade and the stable SPI; its descriptor requires nothing. */
    static final ArchRule API_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {HUMBOLDT_API, HUMBOLDT_SPI},
            Packages.of(Jdk.RUNTIME, HUMBOLDT_API, HUMBOLDT_SPI),
            "module-info.java of humboldt-api");

    /**
     * CLAUDE.md, Architectural constraints 1 and 2: Humboldt consumes the OpenTelemetry API and context, never the
     * OpenTelemetry SDK, and no Netty, grpc-java, OkHttp or Guava. The SDK modules, the context storage, the
     * propagator, the exporter and the runtime read the JDK, the OpenTelemetry API and context, and Humboldt itself.
     */
    static final ArchRule SDK_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {COMMON, HUMBOLDT_CONTEXT, TRACE, METRIC, LOG, W3C, EXPORTER, RUNTIME},
            Packages.of(
                    Jdk.RUNTIME,
                    "javax.management..",
                    "com.sun.management..",
                    OTEL_API,
                    OTEL_CONTEXT,
                    HUMBOLDT_API,
                    HUMBOLDT_SPI,
                    COMMON,
                    HUMBOLDT_CONTEXT,
                    TRACE,
                    METRIC,
                    LOG,
                    W3C,
                    EXPORTER,
                    RUNTIME),
            "CLAUDE.md, Architectural constraints 1 and 2; module-info.java of the SDK modules");

    /**
     * Module descriptors of humboldt-cdi and humboldt-rest: Jakarta CDI, Interceptor, Annotation and REST, the
     * MicroProfile REST Client listener, the OpenTelemetry API, context and instrumentation annotations, Vauban's
     * API; Cassini only through the dispatch classes cassini-processor generates.
     */
    static final ArchRule ADAPTER_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {CDI, REST},
            Packages.of(
                    Jdk.RUNTIME,
                    OTEL_API,
                    OTEL_CONTEXT,
                    "io.opentelemetry.instrumentation.annotations..",
                    "jakarta.enterprise..",
                    "jakarta.inject..",
                    "jakarta.interceptor..",
                    "jakarta.annotation..",
                    "jakarta.ws.rs..",
                    "org.eclipse.microprofile.rest.client..",
                    "io.vidocq.vauban.api..",
                    "io.vidocq.cassini.spi.gen..",
                    HUMBOLDT_API,
                    TRACE,
                    W3C,
                    CDI,
                    REST),
            "module-info.java of humboldt-cdi and humboldt-rest; workspace AGENTS.md, zero external dependency");

    /**
     * Module descriptor of humboldt-otel-interop: the bridge from the OpenTelemetry SDK types to Humboldt's. It is
     * the one module that reads {@code io.opentelemetry.sdk} ({@code requires static}), so that an application or a
     * TCK that holds an OpenTelemetry SDK object can hand it to Humboldt; nothing else may. It also extends the
     * module layer with the two layer helpers of the repackaged OpenTelemetry API ({@code OtelLayerExports}).
     */
    static final ArchRule INTEROP_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {INTEROP},
            Packages.of(
                    Jdk.RUNTIME,
                    OTEL_API,
                    OTEL_CONTEXT,
                    "io.opentelemetry.common..",
                    "io.opentelemetry.sdk..",
                    "io.opentelemetry.extension..",
                    "io.vidocq.humboldt.otel.api.layer..",
                    "io.vidocq.humboldt.otel.context.layer..",
                    HUMBOLDT_API,
                    COMMON,
                    TRACE,
                    METRIC,
                    W3C,
                    INTEROP),
            "module-info.java of humboldt-otel-interop");

    /** CLAUDE.md, Architectural constraints 1: "No import io.opentelemetry.sdk.* in humboldt", the interop apart. */
    static final ArchRule NO_OTEL_SDK = VidocqArchRules.neverDependOn(
            new String[] {
                HUMBOLDT_API, HUMBOLDT_SPI, COMMON, HUMBOLDT_CONTEXT, TRACE, METRIC, LOG, W3C, EXPORTER, CDI, REST, RUNTIME
            },
            new String[] {"io.opentelemetry.sdk.."},
            "CLAUDE.md, Architectural constraints 1");

    /**
     * The module descriptors: humboldt-api is the bottom layer and reads no other Humboldt module; the signal SDKs
     * build on it and on the common SDK; the exporter builds on the SDKs; the adapters and the runtime sit on top
     * and nothing builds on them. The context storage is reached through the OpenTelemetry {@code ServiceLoader}
     * only.
     */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            DESIGN_SOURCE,
            Layer.named("API").in(HUMBOLDT_API, HUMBOLDT_SPI).accessesNoLayer(),
            Layer.named("Common")
                    .in(COMMON)
                    .accessibleOnlyBy("Trace", "Metric", "Log", "Exporter", "Runtime", "Interop"),
            Layer.named("Context").in(HUMBOLDT_CONTEXT).accessibleByNone(),
            Layer.named("Trace").in(TRACE).accessibleOnlyBy("Exporter", "CDI", "REST", "Runtime", "Interop"),
            Layer.named("Metric").in(METRIC).accessibleOnlyBy("Exporter", "Runtime", "Interop"),
            Layer.named("Log").in(LOG).accessibleOnlyBy("Exporter", "Runtime"),
            Layer.named("W3C").in(W3C).accessibleOnlyBy("REST", "Runtime", "Interop"),
            Layer.named("Exporter").in(EXPORTER).accessibleOnlyBy("Runtime"),
            Layer.named("CDI").in(CDI).accessibleByNone(),
            Layer.named("REST").in(REST).accessibleByNone(),
            Layer.named("Runtime").in(RUNTIME).accessibleByNone(),
            Layer.named("Interop").in(INTEROP).accessibleByNone());

    /**
     * internals.adoc, "Generated code and reflective fallback" and "Build-time wiring — and one deliberate proxy
     * exception": the documented reflective code, each with its reason. Nested classes share the entry of their
     * top-level class.
     */
    static final Map<String, String> REFLECTIVE_FALLBACKS = Map.of(
            "io.vidocq.humboldt.cdi.WithSpanInterceptor",
            "internals.adoc, Generated code and reflective fallback: the span plan of a @WithSpan method (name, kind,"
                    + " @SpanAttribute parameters) is read by reflection on its first call and cached per method",
            "io.vidocq.humboldt.rest.HumboldtServerRequestFilter",
            "internals.adoc, Generated code and reflective fallback: the http.route template of a resource method is"
                    + " read from its @Path annotations once and cached per resource class",
            "io.vidocq.humboldt.cdi.HumboldtTelemetryProducers",
            "internals.adoc, Build-time wiring: the one deliberate java.lang.reflect.Proxy, so that an injected Span or"
                    + " Baggage keeps tracking the current context (required by the MicroProfile Telemetry TCK)");

    /**
     * Workspace AGENTS.md, "Maximum static code generation", and internals.adoc: reflection remains only as the
     * documented fallbacks above.
     */
    static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
            REFLECTIVE_FALLBACKS, "workspace AGENTS.md, static code generation; internals.adoc, reflective fallback");

    /**
     * Workspace AGENTS.md, "Virtual Threads everywhere for I/O": the batch processors, the periodic metric reader
     * and the OTLP sender run on virtual threads.
     */
    static final ArchRule NO_PLATFORM_THREADS =
            VidocqArchRules.noPlatformThreads(PlatformThreads.NONE, "workspace AGENTS.md, Virtual Threads");

    /** The one documented {@code ThreadLocal} holder: the context storage (internals.adoc, "Context storage"). */
    static final String CONTEXT_STORAGE_PATTERN =
            "io\\.vidocq\\.humboldt\\.context\\.HumboldtContextStorage(\\$.*)?";

    /**
     * CLAUDE.md, Architectural constraints 3 ("never direct ThreadLocal on the hot path"), with the one decision
     * internals.adoc, "Context storage: ThreadLocal vs ScopedValue" documents: {@code HumboldtContextStorage} keeps
     * the current OpenTelemetry {@code Context} in a {@code ThreadLocal}, which since JDK 21 does not pin a carrier
     * thread, until the ScopedValue storage (a post-MVP option) replaces it.
     */
    static final ArchRule NO_THREAD_LOCAL = noClasses()
            .that()
            .haveNameNotMatching(CONTEXT_STORAGE_PATTERN)
            .should()
            .dependOnClassesThat()
            .areAssignableTo(ThreadLocal.class)
            .because("a ThreadLocal neither follows work across virtual threads nor caches anything; the one"
                    + " documented exception is HumboldtContextStorage (CLAUDE.md, Architectural constraints 3;"
                    + " internals.adoc, Context storage)");

    private ArchitectureRules() {
        // Rules only.
    }
}
