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
package io.vidocq.peano.tests.architecture;

import java.util.Map;

import io.vidocq.archrules.Jdk;
import io.vidocq.archrules.Layer;
import io.vidocq.archrules.Packages;
import io.vidocq.archrules.PlatformThreads;
import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Peano's own rules, which no scanner knows, as ArchUnit rules (Vidocq/vidocq#289). They come from the shared
 * {@code vidocq-archrules} library of {@code vidocq-parent}, set to Peano's packages; each names the document it
 * comes from. A rule holds for whatever classes it is given: {@link ArchitectureRulesTest} gives it the shipped
 * classes, {@link ArchitectureRulesSelfTest} fixtures that break it.
 *
 * <p>The packages of each shipped module: {@code io.vidocq.peano.spi} and {@code io.vidocq.peano.annotation}
 * (peano-api), {@code io.vidocq.peano.core} (peano-core), {@code io.vidocq.peano.processor} (peano-processor) and
 * {@code io.vidocq.peano.compiler} (peano-compiler, this package alone). The opt-in {@code peano-beans-compat}
 * module, the one approved user of {@code java.desktop}, is outside the default reactor and is not covered.
 */
final class ArchitectureRules {

    private static final String AGENTS_CONSTRAINTS = "AGENTS.md, Architecture Constraints Not to Violate";

    private static final String[] API = {"io.vidocq.peano.spi..", "io.vidocq.peano.annotation.."};

    private static final String[] CORE = {"io.vidocq.peano.core.."};

    private static final String[] PROCESSOR = {"io.vidocq.peano.processor.."};

    private static final String[] COMPILER = {"io.vidocq.peano.compiler"};

    /**
     * Constraint 1 and 2: peano-api depends on the {@code jakarta.el} API and nothing else, and not on
     * {@code java.desktop} ({@link Jdk#RUNTIME} has no {@code java.beans}).
     */
    static final ArchRule API_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            API,
            Packages.of(Jdk.RUNTIME, "jakarta.el..", "io.vidocq.peano.spi..", "io.vidocq.peano.annotation.."),
            AGENTS_CONSTRAINTS + ", 1 and 2; module-info.java of peano-api");

    /** Constraint 1 and 2: peano-core builds on peano-api and the {@code jakarta.el} API alone. */
    static final ArchRule CORE_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            CORE,
            Packages.of(
                    Jdk.RUNTIME,
                    "jakarta.el..",
                    "io.vidocq.peano.spi..",
                    "io.vidocq.peano.annotation..",
                    "io.vidocq.peano.core.."),
            AGENTS_CONSTRAINTS + ", 1 and 2; module-info.java of peano-core");

    /** peano-processor: a build-time tool, {@code java.compiler} and peano-api only (module-info.java). */
    static final ArchRule PROCESSOR_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            PROCESSOR,
            Packages.of(
                    Jdk.COMPILER,
                    "io.vidocq.peano.spi..",
                    "io.vidocq.peano.annotation..",
                    "io.vidocq.peano.processor.."),
            AGENTS_CONSTRAINTS + ", 1; module-info.java of peano-processor; workspace AGENTS.md, build-time tools");

    /**
     * peano-compiler: a build-time tool on the Class-File API, the {@code jakarta.el} API, peano-api, and the one
     * package peano-core exports to it, the syntax tree (module-info.java of peano-core, PLAN.md).
     */
    static final ArchRule COMPILER_DEPENDENCIES = VidocqArchRules.dependOnlyOn(
            COMPILER,
            Packages.of(
                    Jdk.COMPILER,
                    "jakarta.el..",
                    "io.vidocq.peano.spi..",
                    "io.vidocq.peano.annotation..",
                    "io.vidocq.peano.core.internal.syntax..",
                    "io.vidocq.peano.compiler"),
            AGENTS_CONSTRAINTS + ", 1 and 4; module-info.java of peano-compiler and peano-core");

    /**
     * AGENTS.md, Architecture: peano-api is the bottom layer; peano-core builds on it; peano-compiler builds on
     * both, at build time; peano-processor builds on peano-api; nothing builds on the last two.
     */
    static final ArchRule LAYERS = VidocqArchRules.layers(
            AGENTS_CONSTRAINTS + "; AGENTS.md, Architecture; the module-info.java files",
            Layer.named("API")
                    .in(API)
                    .accessesNoLayer()
                    .accessibleOnlyBy("Core", "Processor", "Compiler"),
            Layer.named("Core").in(CORE).accessibleOnlyBy("Compiler"),
            Layer.named("Processor").in(PROCESSOR).accessibleByNone(),
            Layer.named("Compiler").in(COMPILER).accessibleByNone());

    /** Workspace AGENTS.md, dependency graph and layering: a {@code *-api} module never depends on a core one. */
    static final ArchRule API_NEVER_ON_CORE = VidocqArchRules.neverDependOn(
            API,
            new String[] {"io.vidocq.peano.core..", "io.vidocq.peano.processor..", "io.vidocq.peano.compiler.."},
            "workspace AGENTS.md, dependency graph; module-info.java of peano-api");

    /**
     * Constraint 1: Peano never depends on Ibarra or Vauban, and no more on the other bricks; they depend on
     * Peano, or only on the {@code jakarta.el} API.
     */
    static final ArchRule STANDS_ALONE = VidocqArchRules.neverDependOn(
            new String[] {"io.vidocq.peano.."},
            new String[] {
                "io.vidocq.ibarra..",
                "io.vidocq.bodoni..",
                "io.vidocq.vauban..",
                "io.vidocq.chappe..",
                "io.vidocq.champollion..",
                "io.vidocq.foy.."
            },
            AGENTS_CONSTRAINTS + ", 1; workspace AGENTS.md, dependency graph");

    private static final String FUNCTION_TYPE = "the EL API hands functions over as java.lang.reflect.Method"
            + " (FunctionMapper.resolveFunction, MethodInfo): the class holds or passes that Method, and invocation"
            + " stays delegated to Jakarta's resolver, without member lookup (internals.adoc, Compiled method"
            + " expressions)";

    private static final String ARRAY = "java.lang.reflect.Array for runtime-typed array coercion, the empty"
            + " operator and array stream sources (EL 6.0 sections 1.25.7, 1.11 and 2.3.1): allocation, element"
            + " access and length, with no member lookup (internals.adoc, AOT compatibility)";

    private static final String METADATA = "the public-member metadata fallback: Class.getMethods of the target,"
            + " reached after the generated accessor tables and governed by peano.reflection (getting-started.adoc,"
            + " Reflection policy; internals.adoc, Compiled method expressions)";

    /**
     * The documented reflective fallbacks, each with its reason. Nested classes share the entry of their
     * top-level class. They are the EL API's own reflective types and the member-metadata fallback behind the
     * generated accessor tables; the shipped classes outside this list use no reflection.
     */
    static final Map<String, String> REFLECTIVE_FALLBACKS = Map.ofEntries(
            Map.entry("io.vidocq.peano.core.internal.evaluation.Bindings", FUNCTION_TYPE),
            Map.entry("io.vidocq.peano.core.internal.evaluation.LambdaBody", FUNCTION_TYPE),
            Map.entry("io.vidocq.peano.core.internal.evaluation.ResolverAccess", FUNCTION_TYPE),
            Map.entry("io.vidocq.peano.core.internal.PeanoValueExpression", FUNCTION_TYPE),
            Map.entry("io.vidocq.peano.spi.CompiledFunctionMapper", FUNCTION_TYPE),
            Map.entry(
                    "io.vidocq.peano.spi.CompiledFunctionValueExpression",
                    FUNCTION_TYPE + "; it checks that the function is public and static"),
            Map.entry("io.vidocq.peano.core.internal.evaluation.Interpreter", FUNCTION_TYPE + "; and " + ARRAY),
            Map.entry("io.vidocq.peano.core.internal.evaluation.Coercions", ARRAY),
            Map.entry("io.vidocq.peano.core.internal.evaluation.StreamELResolver", ARRAY),
            Map.entry("io.vidocq.peano.core.internal.evaluation.MethodMetadata", METADATA),
            Map.entry("io.vidocq.peano.core.internal.PeanoMethodExpression", METADATA),
            Map.entry("io.vidocq.peano.spi.CompiledMethodExpression", METADATA),
            Map.entry(
                    "io.vidocq.peano.core.internal.ExpressionSerialization",
                    "restores the public static function descriptors of a serialized expression with"
                            + " Class.getMethod; the Method objects themselves are not serialized (internals.adoc,"
                            + " serialization proxy)"));

    /** Constraint 5 and workspace AGENTS.md, "Maximum static code generation". */
    static final ArchRule NO_REFLECTION = VidocqArchRules.noReflection(
            REFLECTIVE_FALLBACKS, "workspace AGENTS.md, static code generation; " + AGENTS_CONSTRAINTS + ", 5");

    /** Workspace AGENTS.md, "Virtual Threads everywhere for I/O". */
    static final ArchRule NO_PLATFORM_THREADS = VidocqArchRules.noPlatformThreads(
            PlatformThreads.NONE, "workspace AGENTS.md, Virtual Threads");

    /** Constraint 6: no ThreadLocal; evaluation state travels in the ELContext. */
    static final ArchRule NO_THREAD_LOCAL = VidocqArchRules.noThreadLocal(
            "evaluation state travels in the ELContext, caches are ConcurrentHashMap: a ThreadLocal neither follows a"
                    + " task across virtual threads nor caches anything",
            AGENTS_CONSTRAINTS + ", 6");

    private ArchitectureRules() {
        // Rules only.
    }
}
