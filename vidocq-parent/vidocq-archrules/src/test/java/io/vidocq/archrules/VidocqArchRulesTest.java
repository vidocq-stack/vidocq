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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

import io.vidocq.archrules.fixture.api.ApiReachingIntoCore;
import io.vidocq.archrules.fixture.core.CoreClass;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Each shared rule catches what it forbids and lets through what it allows: a rule that passes on shipped classes
 * proves nothing unless it fails on a class that breaks it. The fixtures below each break one rule.
 */
class VidocqArchRulesTest {

    private static final String SOURCE = "the test document";

    @Test
    void everyRuleNamesTheDocumentItComesFrom() {
        ArchRule rule = VidocqArchRules.noThreadLocal("a ThreadLocal caches nothing", SOURCE);
        assertTrue(rule.getDescription().contains(SOURCE), rule.getDescription());
        assertThrows(IllegalArgumentException.class, () -> VidocqArchRules.noThreadLocal("why", " "));
        assertThrows(IllegalArgumentException.class, () -> VidocqArchRules.noThreadLocal(null, SOURCE));
    }

    @Test
    void theDependencyRuleCatchesALibraryOutsideTheAllowedPackages() {
        ArchRule rule = VidocqArchRules.dependOnlyOn(new String[0], Jdk.ANY, SOURCE);
        RuleAssertions.assertViolated(rule, UsesALibrary.class);
        RuleAssertions.assertKept(rule, UsesTheJdkOnly.class);
    }

    @Test
    void theDependencyRuleLooksAtTheNamedPackagesOnly() {
        String api = "io.vidocq.archrules.fixture.api..";
        String core = "io.vidocq.archrules.fixture.core..";
        ArchRule rule = VidocqArchRules.dependOnlyOn(new String[] {api}, Packages.of(Jdk.ANY, api), SOURCE);
        RuleAssertions.assertViolated(rule, ApiReachingIntoCore.class, CoreClass.class);
        ArchRule allowed = VidocqArchRules.dependOnlyOn(new String[] {api}, Packages.of(Jdk.ANY, api, core), SOURCE);
        RuleAssertions.assertKept(allowed, ApiReachingIntoCore.class, CoreClass.class);
        ArchRule coreOnly = VidocqArchRules.dependOnlyOn(new String[] {core}, Jdk.ANY, SOURCE);
        RuleAssertions.assertKept(coreOnly, ApiReachingIntoCore.class, CoreClass.class);
    }

    @Test
    void theJdkListsDistinguishTheXmlApiFromTheOldServletPackages() {
        ArchRule rule = VidocqArchRules.dependOnlyOn(new String[0], Packages.of(Jdk.RUNTIME, "io.vidocq.archrules.."), SOURCE);
        RuleAssertions.assertViolated(rule, UsesAnOldJavaxPackage.class);
        RuleAssertions.assertKept(rule, UsesTheJdkOnly.class);
    }

    @Test
    void theReflectionRuleCatchesAClassLookupByNameAndAReflectiveRead() {
        ArchRule rule = VidocqArchRules.noReflection(Map.of(), SOURCE);
        RuleAssertions.assertViolated(rule, LooksUpAClassByName.class);
        RuleAssertions.assertViolated(rule, ReadsAMethodReflectively.class);
        RuleAssertions.assertViolated(rule, ReadsAnAnnotation.class);
        RuleAssertions.assertKept(rule, UsesTheJdkOnly.class);
    }

    @Test
    void theReflectionRuleSpareADocumentedFallbackAndItsNestedClasses() {
        ArchRule rule = VidocqArchRules.noReflection(
                Map.of(VidocqArchRulesTest.class.getName(), "loads a class recorded at build time"), SOURCE);
        RuleAssertions.assertKept(rule, LooksUpAClassByName.class, ReadsAMethodReflectively.class, CoreClass.class);
        ArchRule other = VidocqArchRules.noReflection(Map.of("some.Other", "elsewhere"), SOURCE);
        RuleAssertions.assertViolated(other, LooksUpAClassByName.class);
    }

    @Test
    void aReflectiveFallbackWithoutAReasonIsRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> VidocqArchRules.noReflection(Map.of(LooksUpAClassByName.class.getName(), " "), SOURCE));
    }

    @Test
    void theStrictThreadRuleCatchesPoolsAndPlatformThreads() {
        ArchRule rule = VidocqArchRules.noPlatformThreads(PlatformThreads.NONE, SOURCE);
        RuleAssertions.assertViolated(rule, UsesAFixedThreadPool.class);
        RuleAssertions.assertViolated(rule, BuildsAThreadPoolExecutor.class);
        RuleAssertions.assertViolated(rule, StartsAPlatformThread.class);
        RuleAssertions.assertViolated(rule, PoolsOnAVirtualThreadFactory.class);
        RuleAssertions.assertKept(rule, UsesVirtualThreads.class);
    }

    @Test
    void theFactoryThreadRuleLetsAnExecutorTakeAThreadFactory() {
        ArchRule rule = VidocqArchRules.noPlatformThreads(PlatformThreads.UNLESS_THREAD_FACTORY_GIVEN, SOURCE);
        RuleAssertions.assertViolated(rule, UsesAFixedThreadPool.class);
        RuleAssertions.assertViolated(rule, BuildsAThreadPoolExecutor.class);
        RuleAssertions.assertViolated(rule, StartsAPlatformThread.class);
        RuleAssertions.assertKept(rule, PoolsOnAVirtualThreadFactory.class);
        RuleAssertions.assertKept(rule, UsesVirtualThreads.class);
    }

    @Test
    void theThreadLocalRuleCatchesAThreadLocal() {
        ArchRule rule = VidocqArchRules.noThreadLocal("a ThreadLocal caches nothing", SOURCE);
        RuleAssertions.assertViolated(rule, KeepsAThreadLocal.class);
        RuleAssertions.assertKept(rule, UsesVirtualThreads.class);
    }

    @Test
    void theNeverDependRuleCatchesAForbiddenPackage() {
        ArchRule rule = VidocqArchRules.neverDependOn(
                new String[] {"io.vidocq.archrules.fixture.api.."}, new String[] {"io.vidocq.archrules.fixture.core.."}, SOURCE);
        RuleAssertions.assertViolated(rule, ApiReachingIntoCore.class, CoreClass.class);
        ArchRule reverse = VidocqArchRules.neverDependOn(
                new String[] {"io.vidocq.archrules.fixture.core.."}, new String[] {"io.vidocq.archrules.fixture.api.."}, SOURCE);
        RuleAssertions.assertKept(reverse, ApiReachingIntoCore.class, CoreClass.class);
    }

    @Test
    void theLayerRuleCatchesAnApiReachingIntoTheCore() {
        ArchRule rule = VidocqArchRules.layers(
                SOURCE,
                Layer.named("API").in("io.vidocq.archrules.fixture.api..").accessesNoLayer(),
                Layer.named("Core").in("io.vidocq.archrules.fixture.core..").accessibleByNone());
        RuleAssertions.assertViolated(rule, ApiReachingIntoCore.class, CoreClass.class);
        ArchRule reverse = VidocqArchRules.layers(
                SOURCE,
                Layer.named("API").in("io.vidocq.archrules.fixture.api..").accessibleByNone(),
                Layer.named("Core").in("io.vidocq.archrules.fixture.core..").accessesNoLayer());
        RuleAssertions.assertKept(reverse, ApiReachingIntoCore.class, CoreClass.class);
    }

    @Test
    void theLayerRuleHonoursTheListOfLayersAllowedToAccess() {
        ArchRule rule = VidocqArchRules.layers(
                SOURCE,
                Layer.named("API").in("io.vidocq.archrules.fixture.api.."),
                Layer.named("Core").in("io.vidocq.archrules.fixture.core..").accessibleOnlyBy("API"));
        RuleAssertions.assertKept(rule, ApiReachingIntoCore.class, CoreClass.class);
        ArchRule closed = VidocqArchRules.layers(
                SOURCE,
                Layer.named("API").in("io.vidocq.archrules.fixture.api.."),
                Layer.named("Core").in("io.vidocq.archrules.fixture.core..").accessibleOnlyBy());
        RuleAssertions.assertViolated(closed, ApiReachingIntoCore.class, CoreClass.class);
    }

    @Test
    void theAssertionHelpersFailWhenTheExpectationIsWrong() {
        ArchRule rule = VidocqArchRules.noThreadLocal("why", SOURCE);
        assertThrows(AssertionError.class, () -> RuleAssertions.assertViolated(rule, UsesVirtualThreads.class));
        assertThrows(AssertionError.class, () -> RuleAssertions.assertKept(rule, KeepsAThreadLocal.class));
        assertTrue(List.of(Jdk.ANY).contains("java.."));
    }

    static final class UsesALibrary {
        void check() {
            org.junit.jupiter.api.Assertions.fail("a library outside the JDK");
        }
    }

    static final class UsesAnOldJavaxPackage {
        javax.annotation.processing.Generated marker() {
            return null;
        }
    }

    static final class UsesTheJdkOnly {
        String join(List<String> parts) {
            return String.join(",", parts);
        }
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

    static final class ReadsAnAnnotation {
        boolean marked() {
            return String.class.isAnnotationPresent(Deprecated.class);
        }
    }

    static final class UsesAFixedThreadPool {
        ExecutorService pool() {
            return Executors.newFixedThreadPool(4);
        }
    }

    static final class BuildsAThreadPoolExecutor {
        ExecutorService pool() {
            return new ThreadPoolExecutor(
                    1, 1, 0, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>());
        }
    }

    static final class PoolsOnAVirtualThreadFactory {
        ExecutorService pool() {
            ThreadFactory factory = Thread.ofVirtual().factory();
            return Executors.newScheduledThreadPool(1, factory);
        }
    }

    static final class StartsAPlatformThread {
        Thread start(Runnable task) {
            return Thread.ofPlatform().start(task);
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
