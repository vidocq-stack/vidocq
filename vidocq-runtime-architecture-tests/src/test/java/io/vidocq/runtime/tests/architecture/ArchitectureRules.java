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
package io.vidocq.runtime.tests.architecture;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadPoolExecutor;

import io.vidocq.archrules.Jdk;
import io.vidocq.archrules.Layer;
import io.vidocq.archrules.Packages;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The rules of the Vidocq runtime as ArchUnit rules (Vidocq/vidocq#289), from the shared {@code vidocq-archrules}
 * library of {@code vidocq-parent}; each names the document it comes from. They apply to the runtime SPI
 * ({@code io.vidocq.runtime.spi}), the runtime core ({@code io.vidocq.runtime.core}) and the extensions
 * ({@code io.vidocq.runtime.extensions}).
 */
final class ArchitectureRules {

    private static final String SPI = "io.vidocq.runtime.spi..";
    private static final String CORE = "io.vidocq.runtime.core..";
    private static final String EXTENSIONS = "io.vidocq.runtime.extensions..";

    /** Module descriptor of vidocq-runtime-spi: it requires Vauban's core transitively, and CDI through it. */
    static final ArchRule SPI_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {SPI},
            Packages.of(Jdk.RUNTIME, "io.vidocq.vauban..", "jakarta.enterprise..", "jakarta.inject..", SPI),
            "module-info.java of vidocq-runtime-spi; CLAUDE.md, zero runtime dependency");

    /** Module descriptor of vidocq-runtime-core: the SPI, Vauban and the Jakarta CDI APIs, nothing else. */
    static final ArchRule CORE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            new String[] {CORE},
            Packages.of(
                    Jdk.RUNTIME,
                    "io.vidocq.vauban..",
                    "jakarta.enterprise..",
                    "jakarta.inject..",
                    "jakarta.annotation..",
                    SPI,
                    CORE),
            "module-info.java of vidocq-runtime-core; CLAUDE.md, zero runtime dependency in the core");

    /**
     * CLAUDE.md, Extension model: an extension implements the SPI and nothing of the runtime itself; the core
     * discovers it through {@code ServiceLoader}, so no extension reaches the core, the tooling, the Maven plugin,
     * the CLI or the dev services.
     */
    static final ArchRule EXTENSIONS_ON_THE_SPI_ONLY = VidocqArchRules.neverDependOn(
            new String[] {EXTENSIONS},
            new String[] {
                CORE,
                "io.vidocq.runtime.tooling..",
                "io.vidocq.runtime.cli..",
                "io.vidocq.runtime.maven..",
                "io.vidocq.runtime.devservices..",
                "io.vidocq.runtime.junit.."
            },
            "CLAUDE.md, Extension model; the module-info.java files of the extensions");

    /** The core is reached by nothing here, the extensions neither; both build on the SPI. */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            "CLAUDE.md, Architecture and Extension model; the module-info.java files",
            Layer.named("SPI").in(SPI).accessesNoLayer(),
            Layer.named("Core").in(CORE).accessibleByNone(),
            Layer.named("Extensions").in(EXTENSIONS).accessibleByNone());

    private static final String LAUNCH = "the launch of the application: its main class and its classes are known by"
            + " name only, and the module layer that holds them is built at run time (internals.adoc, Class loading)";

    private static final String BY_NAME = "looks a class up by name without initialising it: the core must start"
            + " without the optional class (a brick, the dev console, a JDBC driver) on its module path";

    private static final String TYPE_CURRENCY = "carries java.lang.reflect.Type, the currency of the Jakarta and"
            + " container metadata APIs; it reads no member";

    private static final String CONTAINER_TYPES = "reads the repository interfaces the container holds, by reflection,"
            + " once per boot, for the startup report and the dev console; best effort, never thrown";

    private static final String OPTIONAL_CLASS = "loads by name a class of a library the extension does not require"
            + " (optional, or chosen by configuration)";

    /**
     * AGENTS.md and CLAUDE.md, "Compile-time over reflection", and internals.adoc, "Generated code and reflective
     * fallback": the documented reflective code, each with its reason. Nested classes share the entry of their
     * top-level class.
     */
    static final Map<String, String> REFLECTIVE_FALLBACKS = Map.ofEntries(
            Map.entry("io.vidocq.runtime.core.ApplicationMain", "chooses the main method of the application exactly as"
                    + " the Java SE launcher does (JEP 512: static or not, any visibility), so it reads the declared"
                    + " methods of a class known by name only"),
            Map.entry("io.vidocq.runtime.core.Vidocq", LAUNCH),
            Map.entry("io.vidocq.runtime.core.VidocqAppLayer", LAUNCH),
            Map.entry("io.vidocq.runtime.core.LayerJdbcDrivers", "deregisters the JDBC drivers a closing application"
                    + " layer defined, so it loads them from that layer by name"),
            Map.entry("io.vidocq.runtime.core.ModuleAccessDiagnostics", "recognises the exception the JDK throws when"
                    + " a module does not open a package; it reads no member"),
            Map.entry("io.vidocq.runtime.core.banner.DevConsoleProbe", BY_NAME),
            Map.entry("io.vidocq.runtime.core.banner.StartupIdentity", BY_NAME),
            Map.entry("io.vidocq.runtime.core.report.ContributorContext", TYPE_CURRENCY),
            Map.entry("io.vidocq.runtime.extensions.essentials.chappe.ChappeMountConfigExtension", OPTIONAL_CLASS
                    + ": the handler class of a mount named by vidocq.http.mount.<name>.*"),
            Map.entry("io.vidocq.runtime.extensions.essentials.devconsole.JvmPanel", BY_NAME
                    + " (com.sun.management, an optional JDK module)"),
            Map.entry("io.vidocq.runtime.extensions.essentials.migration.flyway.ApplicationLayerMigrations",
                    "finds the migrations in the application's own module layer, where Flyway's class-path scanner"
                            + " finds nothing (vidocq#96); it loads each candidate by name to keep the concrete"
                            + " JavaMigration classes"),
            Map.entry("io.vidocq.runtime.extensions.microprofile.humboldt.HumboldtExtension", OPTIONAL_CLASS
                    + ": MicroProfile Config, read by reflection when it is on the module path"),
            Map.entry("io.vidocq.runtime.extensions.jakartaee.web.mansart.data.RepositoryReader", CONTAINER_TYPES),
            Map.entry("io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueBuilder", CONTAINER_TYPES),
            Map.entry("io.vidocq.runtime.extensions.jakartaee.web.mansart.data.TypeNames", TYPE_CURRENCY),
            Map.entry("io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RepositoryActions",
                    "dev console only: runs the methods of a repository the page names"),
            Map.entry("io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RepositoryMethods",
                    "dev console only: lists the methods of a repository the page offers"),
            Map.entry("io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.ResultJson",
                    "dev console only: prints a result array"),
            Map.entry("io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.Signature",
                    "dev console only: describes a repository method's parameters"),
            Map.entry("io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.WriteKinds",
                    "dev console only: tells which repository methods write"),
            Map.entry("io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.CatalogueLivePanel",
                    "dev console only: carries java.lang.reflect.Method to run a repository action"),
            Map.entry("io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.McpChecks", "reads the parameter names"
                    + " and annotations of the methods langchain4j-cdi registers as tools, prompts and resources, to"
                    + " raise VIDOCQ-MCP-001 and VIDOCQ-MCP-005 at boot; it calls nothing"),
            Map.entry("io.vidocq.runtime.extensions.jakartaee.core.cassini.ApplicationPaths", "reads @ApplicationPath"
                    + " off an Application bean, the source for classes compiled without cassini-processor"
                    + " (Vidocq/vidocq#219)"),
            Map.entry("io.vidocq.runtime.extensions.jakartaee.core.cassini.live.RestMount", "reads the annotations of the"
                    + " mounted resource classes to describe them in the startup report and the dev console"));

    static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
            REFLECTIVE_FALLBACKS, "AGENTS.md and CLAUDE.md, compile-time over reflection");

    /** The two platform threads of the runtime, with their reason; nothing else may start one. */
    static final Map<String, String> PLATFORM_THREADS = Map.of(
            "io.vidocq.runtime.core.VidocqBootstrap",
            "the JVM shutdown hook: Runtime.addShutdownHook takes a Thread that runs once, at exit, and does no I/O"
                    + " of the application",
            "io.vidocq.runtime.extensions.essentials.devconsole.DevConsoleExtension",
            "the history ticker, a platform thread on purpose: it shows in Thread.getAllStackTraces(), which is how"
                    + " the dev-reload tests prove that a reload leaves exactly one ticker; one daemon thread per boot,"
                    + " mostly asleep");

    private static final String PLATFORM_POOLS = "newFixedThreadPool|newCachedThreadPool|newSingleThreadExecutor"
            + "|newScheduledThreadPool|newSingleThreadScheduledExecutor|newWorkStealingPool"
            + "|defaultThreadFactory|privilegedThreadFactory";

    /**
     * Workspace AGENTS.md, "Virtual Threads everywhere for I/O": no platform-thread pool and no platform thread, but
     * the two of {@link #PLATFORM_THREADS}. The same rule as {@code VidocqArchRules.noPlatformThreads(NONE, ...)},
     * which has no exemption list, applied to the other classes.
     */
    static final ArchRule NO_PLATFORM_THREADS = noClasses()
            .that(DescribedPredicate.describe(
                    "are not a documented platform thread", type -> !PLATFORM_THREADS.containsKey(topLevel(type))))
            .should()
            .callMethodWhere(target(owner(type(Executors.class))).and(target(nameMatching(PLATFORM_POOLS))))
            .orShould()
            .callConstructorWhere(target(owner(assignableTo(ThreadPoolExecutor.class))))
            .orShould()
            .callConstructorWhere(target(owner(assignableTo(ForkJoinPool.class))))
            .orShould()
            .callMethod(Thread.class, "ofPlatform")
            .orShould()
            .callConstructorWhere(target(owner(type(Thread.class))))
            .because("every task runs on a virtual thread, no platform-thread pool (workspace AGENTS.md, Virtual"
                    + " Threads; the documented exceptions are in PLATFORM_THREADS)");

    /** The name of the top-level class of a class, nested ones included. */
    static String topLevel(JavaClass type) {
        JavaClass top = type;
        while (top.getEnclosingClass().isPresent()) {
            top = top.getEnclosingClass().get();
        }
        return top.getName();
    }

    /** CLAUDE.md: "use ScopedValue for the @RequestScoped context, never ThreadLocal (pinning)". */
    static final ArchRule NO_THREAD_LOCAL = VidocqArchRules.noThreadLocal(
            "a ThreadLocal pins a virtual thread's context to its carrier; ScopedValue follows the call",
            "CLAUDE.md, Project overview; AGENTS.md, virtual-thread native");

    private ArchitectureRules() {
        // Rules only.
    }
}
