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
package io.vidocq.archrules;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitCallTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.elements.GivenClassesConjunction;
import com.tngtech.archunit.library.Architectures.LayeredArchitecture;

/**
 * The architecture rules of the Vidocq ecosystem (Vidocq/vidocq#289), one factory per rule. Each brick builds the
 * rules for its own packages in its architecture tests, and runs them on the classes it ships. A rule holds for
 * whatever classes it is given, so a brick also tests each one on fixtures that break it ({@link RuleAssertions}).
 *
 * <p>Every rule takes the {@code source}, the document it comes from (workspace {@code AGENTS.md}, the reference
 * page of the module), and names it in its description: a failure tells the reader which rule was broken and where
 * it is written down.
 */
public final class VidocqArchRules {

    private static final String REFLECTIVE_CALLS = "forName|newInstance|getDeclared.*|getMethods?|getFields?"
            + "|getConstructors?|getRecordComponents|getAnnotations?|getAnnotationsByType|isAnnotationPresent";

    private static final String PLATFORM_POOLS = "newFixedThreadPool|newCachedThreadPool|newSingleThreadExecutor"
            + "|newScheduledThreadPool|newSingleThreadScheduledExecutor|newWorkStealingPool"
            + "|defaultThreadFactory|privilegedThreadFactory";

    private static final DescribedPredicate<CodeUnitCallTarget> WITHOUT_A_THREAD_FACTORY = DescribedPredicate.describe(
            "takes no ThreadFactory",
            call -> call.getRawParameterTypes().stream().noneMatch(p -> p.isEquivalentTo(ThreadFactory.class)));

    /**
     * The zero-dependency rule: the classes of {@code subjectPackages} (every class when empty) depend only on the
     * JDK and on the {@code allowedPackages}, the module's own packages included.
     *
     * @param subjectPackages the packages the rule applies to, empty for every class imported
     * @param allowedPackages the packages the subject may depend on, as {@link Jdk} lists plus the module's own
     * @param source the document the rule comes from
     */
    public static ArchRule dependOnlyOn(String[] subjectPackages, String[] allowedPackages, String source) {
        String where = subjectPackages.length == 0 ? "the shipped classes" : "the classes of " + String.join(", ", subjectPackages);
        GivenClassesConjunction subject = subjectPackages.length == 0
                ? classes().that().resideInAnyPackage("..")
                : classes().that().resideInAnyPackage(subjectPackages);
        return subject.should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage(allowedPackages)
                .because(reason(where + " depend on nothing but " + String.join(", ", allowedPackages), source));
    }

    /**
     * The reflection-free rule: no {@code java.lang.reflect}, no class looked up by name, no member or annotation
     * read reflectively, except in the documented fallbacks. A nested class shares the entry of its top-level
     * class.
     *
     * @param documentedFallbacks the top-level class names allowed to use reflection, each with its reason
     * @param source the document the rule comes from, which also documents the fallbacks
     */
    public static ArchRule noReflection(Map<String, String> documentedFallbacks, String source) {
        documentedFallbacks.forEach((name, why) -> {
            if (why == null || why.isBlank()) {
                throw new IllegalArgumentException("a reflective fallback needs its documented reason: " + name);
            }
        });
        DescribedPredicate<JavaClass> notAFallback = DescribedPredicate.describe(
                "are not a documented reflective fallback", type -> !documentedFallbacks.containsKey(topLevel(type)));
        return noClasses()
                .that(notAFallback)
                .should()
                .dependOnClassesThat()
                .resideInAPackage("java.lang.reflect..")
                .orShould()
                .callMethodWhere(target(owner(type(Class.class))).and(target(nameMatching(REFLECTIVE_CALLS))))
                .because(reason("no reflection outside the documented fallbacks", source));
    }

    /**
     * The virtual-threads rule: no platform-thread pool and no platform thread in I/O code.
     *
     * @param policy how strictly a thread factory is read
     * @param source the document the rule comes from
     */
    public static ArchRule noPlatformThreads(PlatformThreads policy, String source) {
        boolean factoryTolerated = policy == PlatformThreads.UNLESS_THREAD_FACTORY_GIVEN;
        DescribedPredicate<CodeUnitCallTarget> pools = factoryTolerated ? WITHOUT_A_THREAD_FACTORY : DescribedPredicate.alwaysTrue();
        return noClasses()
                .should()
                .callMethodWhere(target(owner(type(Executors.class)))
                        .and(target(nameMatching(PLATFORM_POOLS)))
                        .and(target(pools)))
                .orShould()
                .callConstructorWhere(target(owner(assignableTo(ThreadPoolExecutor.class))).and(target(pools)))
                .orShould()
                .callConstructorWhere(target(owner(assignableTo(ForkJoinPool.class))))
                .orShould()
                .callMethod(Thread.class, "ofPlatform")
                .orShould()
                .callConstructorWhere(target(owner(type(Thread.class))))
                .because(reason("every task runs on a virtual thread, no platform-thread pool", source));
    }

    /**
     * The virtual-threads rule for thread-locals: no {@link ThreadLocal}, which neither follows a request across
     * virtual threads nor caches anything shared.
     *
     * @param why the reason in this brick, one sentence
     * @param source the document the rule comes from
     */
    public static ArchRule noThreadLocal(String why, String source) {
        if (why == null || why.isBlank()) {
            throw new IllegalArgumentException("a rule needs its reason");
        }
        return noClasses()
                .should()
                .dependOnClassesThat()
                .areAssignableTo(ThreadLocal.class)
                .because(reason(why, source));
    }

    /**
     * The layering rule in its plainest form: no class of {@code fromPackages} depends on a class of
     * {@code forbiddenPackages} (a foundational brick on another, an {@code *-api} on its {@code *-core}).
     */
    public static ArchRule neverDependOn(String[] fromPackages, String[] forbiddenPackages, String source) {
        return noClasses()
                .that()
                .resideInAnyPackage(fromPackages)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(forbiddenPackages)
                .because(reason(String.join(", ", fromPackages) + " never depend on " + String.join(", ", forbiddenPackages), source));
    }

    /** The layering rule for the layers of one brick, in the order they are given. */
    public static ArchRule layers(String source, Layer... layers) {
        LayeredArchitecture architecture = layeredArchitecture().consideringOnlyDependenciesInLayers();
        for (Layer layer : layers) {
            architecture = architecture.layer(layer.name()).definedBy(layer.packages());
        }
        for (Layer layer : layers) {
            if (layer.accessesNoLayerAtAll()) {
                architecture = architecture.whereLayer(layer.name()).mayNotAccessAnyLayer();
            }
            if (layer.accessibleOnlyByLayers() != null && layer.accessibleOnlyByLayers().isEmpty()) {
                architecture = architecture.whereLayer(layer.name()).mayNotBeAccessedByAnyLayer();
            } else if (layer.accessibleOnlyByLayers() != null) {
                architecture = architecture.whereLayer(layer.name())
                        .mayOnlyBeAccessedByLayers(layer.accessibleOnlyByLayers().toArray(String[]::new));
            }
        }
        return architecture.because(reason("the layers hold", source));
    }

    private static String reason(String what, String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("a rule names the document it comes from");
        }
        return what + " (" + source + ")";
    }

    private static String topLevel(JavaClass type) {
        JavaClass top = type;
        while (top.getEnclosingClass().isPresent()) {
            top = top.getEnclosingClass().get();
        }
        return top.getName();
    }

    private VidocqArchRules() {
        // Factories only.
    }
}
