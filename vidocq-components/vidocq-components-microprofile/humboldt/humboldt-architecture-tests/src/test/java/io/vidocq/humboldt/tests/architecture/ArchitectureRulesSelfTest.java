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

import static io.vidocq.humboldt.tests.architecture.HumboldtClasses.SHIPPED;
import static io.vidocq.humboldt.tests.architecture.HumboldtClasses.SHIPPED_AND_FIXTURES;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;

import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.vidocq.humboldt.cdi.archfixture.CdiReachingIntoTheExporter;
import io.vidocq.humboldt.otel.interop.archfixture.InteropReachingIntoTheLogSdk;
import io.vidocq.humboldt.sdk.trace.archfixture.TraceReachingIntoTheRuntime;
import io.vidocq.humboldt.sdk.trace.archfixture.TraceUsingALibrary;
import io.vidocq.humboldt.spi.archfixture.ApiReachingIntoTrace;

import io.vidocq.archrules.VidocqArchRules;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Each architecture rule catches what it forbids: a rule that passes on the shipped classes proves nothing unless
 * it fails on a class that breaks it. A violation must name the fixture, so that a rule failing for another
 * reason (an empty layer, a class the rule does not select) does not count.
 */
class ArchitectureRulesSelfTest {

    /** The module and layer rules, each with a fixture of an {@code archfixture} package that breaks it. */
    static Stream<Arguments> moduleRuleBreaches() {
        return Stream.of(
                ruleWith("API_DEPENDENCIES", ArchitectureRules.API_DEPENDENCIES, ApiReachingIntoTrace.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, ApiReachingIntoTrace.class),
                ruleWith("SDK_DEPENDENCIES", ArchitectureRules.SDK_DEPENDENCIES, TraceUsingALibrary.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, TraceReachingIntoTheRuntime.class),
                ruleWith("ADAPTER_DEPENDENCIES", ArchitectureRules.ADAPTER_DEPENDENCIES, CdiReachingIntoTheExporter.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, CdiReachingIntoTheExporter.class),
                ruleWith("INTEROP_DEPENDENCIES", ArchitectureRules.INTEROP_DEPENDENCIES, InteropReachingIntoTheLogSdk.class));
    }

    @ParameterizedTest(name = "{0} catches {1}")
    @MethodSource("moduleRuleBreaches")
    void eachModuleRuleCatchesAFixtureAmongTheShippedClasses(ArchRule rule, Class<?> fixture) {
        assertNamesTheFixture(rule, SHIPPED_AND_FIXTURES, fixture);
    }

    /** The code rules, each with a fixture below that breaks it. */
    static Stream<Arguments> codeRuleBreaches() {
        return Stream.of(
                ruleWith("NO_REFLECTION", ArchitectureRules.NO_REFLECTION, LooksUpAClassByName.class),
                ruleWith("NO_REFLECTION", ArchitectureRules.NO_REFLECTION, ReadsAMethodReflectively.class),
                ruleWith("NO_REFLECTION", ArchitectureRules.NO_REFLECTION, ReadsAnAnnotationReflectively.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, UsesAFixedThreadPool.class),
                ruleWith(
                        "NO_PLATFORM_THREADS",
                        ArchitectureRules.NO_PLATFORM_THREADS,
                        SchedulesOnAPlatformThread.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, BuildsAThreadPoolExecutor.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, StartsAPlatformThread.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, CreatesAThread.class),
                ruleWith("NO_THREAD_LOCAL", ArchitectureRules.NO_THREAD_LOCAL, KeepsAThreadLocal.class));
    }

    @ParameterizedTest(name = "{0} catches {1}")
    @MethodSource("codeRuleBreaches")
    void eachCodeRuleCatchesAFixture(ArchRule rule, Class<?> fixture) {
        assertNamesTheFixture(rule, new ClassFileImporter().importClasses(fixture), fixture);
    }

    /** What the code rules let through: look-alikes of the breaches that keep the rule. */
    static Stream<Arguments> codeRuleLookAlikes() {
        return Stream.of(
                ruleWith("NO_REFLECTION", ArchitectureRules.NO_REFLECTION, LooksUpACharsetByName.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, UsesVirtualThreads.class),
                ruleWith("NO_THREAD_LOCAL", ArchitectureRules.NO_THREAD_LOCAL, UsesVirtualThreads.class));
    }

    @ParameterizedTest(name = "{0} keeps {1}")
    @MethodSource("codeRuleLookAlikes")
    void eachCodeRuleKeepsALookAlike(ArchRule rule, Class<?> fixture) {
        var result = rule.evaluate(new ClassFileImporter().importClasses(fixture));
        assertFalse(result.hasViolation(), () -> result.getFailureReport().toString());
    }

    @Test
    void theReflectionAllowlistNamesShippedClasses() {
        // A stale entry would exempt a class that no longer exists, or let a new class take its name.
        for (String allowed : ArchitectureRules.REFLECTIVE_FALLBACKS.keySet()) {
            assertTrue(SHIPPED.contain(allowed), () -> allowed + " is not a shipped class");
        }
    }

    @Test
    void everyReflectionAllowlistEntryIsNeeded() {
        // An entry the shipped classes no longer need would exempt a class for nothing, and hide a later breach.
        List<String> unneeded = new java.util.ArrayList<>();
        for (String allowed : ArchitectureRules.REFLECTIVE_FALLBACKS.keySet()) {
            var without = new java.util.HashMap<>(ArchitectureRules.REFLECTIVE_FALLBACKS);
            without.remove(allowed);
            if (!VidocqArchRules.noReflection(without, "allowlist check").evaluate(SHIPPED).hasViolation()) {
                unneeded.add(allowed);
            }
        }
        assertTrue(unneeded.isEmpty(), () -> "these entries need no reflection, drop them: " + unneeded);
    }

    @Test
    void theThreadLocalExemptionIsNeeded() {
        // The context storage is the one documented ThreadLocal: without it, the plain rule must fail on it.
        var plain = VidocqArchRules.noThreadLocal("exemption check", "ArchitectureRulesSelfTest");
        var violations = plain.evaluate(SHIPPED).getFailureReport().getDetails();
        assertTrue(
                violations.stream().anyMatch(d -> d.contains("io.vidocq.humboldt.context.HumboldtContextStorage")),
                "HumboldtContextStorage no longer keeps a ThreadLocal: drop the exemption");
        assertTrue(
                violations.stream().noneMatch(d -> !d.contains("io.vidocq.humboldt.context.HumboldtContextStorage")),
                () -> "another class keeps a ThreadLocal: " + violations);
    }

    private static Arguments ruleWith(String name, ArchRule rule, Class<?> fixture) {
        return Arguments.of(named(name, rule), fixture);
    }

    private static void assertNamesTheFixture(ArchRule rule, JavaClasses classes, Class<?> fixture) {
        List<String> details = rule.evaluate(classes).getFailureReport().getDetails();
        assertTrue(
                details.stream().anyMatch(detail -> detail.contains(fixture.getName())),
                () -> "expected a violation by " + fixture.getName() + " of: " + rule.getDescription() + ", got "
                        + details);
    }

    static final class LooksUpAClassByName {
        Class<?> find(String name) throws ClassNotFoundException {
            return Class.forName(name);
        }
    }

    static final class ReadsAMethodReflectively {
        Object method() throws NoSuchMethodException {
            return String.class.getDeclaredMethod("length");
        }
    }

    static final class ReadsAnAnnotationReflectively {
        boolean deprecated(Class<?> type) {
            return type.isAnnotationPresent(Deprecated.class);
        }
    }

    static final class LooksUpACharsetByName {
        Charset charset(String name) {
            return Charset.forName(name);
        }
    }

    static final class UsesAFixedThreadPool {
        ExecutorService pool() {
            return Executors.newFixedThreadPool(4);
        }
    }

    static final class SchedulesOnAPlatformThread {
        ScheduledExecutorService reaper() {
            return Executors.newSingleThreadScheduledExecutor();
        }
    }

    static final class BuildsAThreadPoolExecutor {
        ExecutorService pool() {
            return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        }
    }

    static final class StartsAPlatformThread {
        Thread start(Runnable task) {
            return Thread.ofPlatform().start(task);
        }
    }

    static final class CreatesAThread {
        Thread create(Runnable task) {
            return new Thread(task);
        }
    }

    static final class UsesVirtualThreads {
        ExecutorService executor() {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
    }

    static final class KeepsAThreadLocal {
        private static final ThreadLocal<StringBuilder> BUFFER = ThreadLocal.withInitial(StringBuilder::new);

        StringBuilder buffer() {
            return BUFFER.get();
        }
    }
}
