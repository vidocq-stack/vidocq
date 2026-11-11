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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
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
        ArchRule rule = VidocqArchRules.dependOnlyOn(new String[0], Jdk.any(), SOURCE);
        RuleAssertions.assertViolated(rule, UsesALibrary.class);
        RuleAssertions.assertKept(rule, UsesTheJdkOnly.class);
    }

    @Test
    void theDependencyRuleLooksAtTheNamedPackagesOnly() {
        String api = "io.vidocq.archrules.fixture.api..";
        String core = "io.vidocq.archrules.fixture.core..";
        ArchRule rule = VidocqArchRules.dependOnlyOn(new String[] {api}, Packages.of(Jdk.any(), api), SOURCE);
        RuleAssertions.assertViolated(rule, ApiReachingIntoCore.class, CoreClass.class);
        ArchRule allowed = VidocqArchRules.dependOnlyOn(new String[] {api}, Packages.of(Jdk.any(), api, core), SOURCE);
        RuleAssertions.assertKept(allowed, ApiReachingIntoCore.class, CoreClass.class);
        ArchRule coreOnly = VidocqArchRules.dependOnlyOn(new String[] {core}, Jdk.any(), SOURCE);
        RuleAssertions.assertKept(coreOnly, ApiReachingIntoCore.class, CoreClass.class);
    }

    @Test
    void theJdkListsDistinguishTheXmlApiFromTheOldServletPackages() {
        ArchRule rule = VidocqArchRules.dependOnlyOn(new String[0], Packages.of(Jdk.runtime(), "io.vidocq.archrules.."), SOURCE);
        RuleAssertions.assertViolated(rule, UsesAnOldJavaxPackage.class);
        RuleAssertions.assertKept(rule, UsesTheJdkOnly.class);
    }

    @Test
    @SuppressWarnings("deprecation")
    void theReflectionRuleCatchesAClassLookupByNameAndAReflectiveRead() {
        ArchRule rule = VidocqArchRules.noReflection(Map.of(), SOURCE);
        RuleAssertions.assertViolated(rule, LooksUpAClassByName.class);
        RuleAssertions.assertViolated(rule, ReadsAMethodReflectively.class);
        RuleAssertions.assertViolated(rule, ReadsAnAnnotation.class);
        RuleAssertions.assertKept(rule, UsesTheJdkOnly.class);
    }

    @Test
    @SuppressWarnings("deprecation")
    void theReflectionRuleSpareADocumentedFallbackAndItsNestedClasses() {
        ArchRule rule = VidocqArchRules.noReflection(
                Map.of(VidocqArchRulesTest.class.getName(), "loads a class recorded at build time"), SOURCE);
        RuleAssertions.assertKept(rule, LooksUpAClassByName.class, ReadsAMethodReflectively.class, CoreClass.class);
        ArchRule other = VidocqArchRules.noReflection(Map.of("some.Other", "elsewhere"), SOURCE);
        RuleAssertions.assertViolated(other, LooksUpAClassByName.class);
    }

    @Test
    @SuppressWarnings("deprecation")
    void aReflectiveFallbackWithoutAReasonIsRefused() {
        Map<String, String> fallbackWithoutReason = Map.of(LooksUpAClassByName.class.getName(), " ");
        assertThrows(
                IllegalArgumentException.class, () -> VidocqArchRules.noReflection(fallbackWithoutReason, SOURCE));
    }

    @Test
    void theIntentRuleLetsATypeTokenThroughAndStopsAMemberRead() {
        ArchRule rule = VidocqArchRules.noReflection(ReflectionExceptions.none(), SOURCE);
        RuleAssertions.assertKept(rule, HoldsATypeToken.class, UsesTheJdkOnly.class);
        RuleAssertions.assertViolated(rule, LooksUpAClassByName.class);
        RuleAssertions.assertViolated(rule, ReadsAMethodReflectively.class);
        RuleAssertions.assertViolated(rule, ReadsAnAnnotation.class);
        RuleAssertions.assertViolated(rule, InvokesAMethod.class);
        RuleAssertions.assertViolated(rule, LoadsByTheClassLoader.class);
    }

    @Test
    void anExceptionByPackageSparesThePackageOnly() {
        ReflectionExceptions here = ReflectionExceptions.builder()
                .inPackage("io.vidocq.archrules", "the adapter that reads user annotations")
                .build();
        RuleAssertions.assertKept(
                VidocqArchRules.noReflection(here, SOURCE), LooksUpAClassByName.class, InvokesAMethod.class, CoreClass.class);
        ReflectionExceptions elsewhere =
                ReflectionExceptions.builder().inPackage("some.other..", "elsewhere").build();
        RuleAssertions.assertViolated(VidocqArchRules.noReflection(elsewhere, SOURCE), LooksUpAClassByName.class);
    }

    @Test
    void anExceptionByAnnotationSparesTheAnnotatedClassAndItsNestedClasses() {
        ReflectionExceptions annotated = ReflectionExceptions.builder()
                .annotatedWith(ReflectiveAdapter.class, "reads user annotations at run time")
                .build();
        ArchRule rule = VidocqArchRules.noReflection(annotated, SOURCE);
        RuleAssertions.assertKept(rule, AnnotatedAdapter.class, AnnotatedAdapter.Inner.class, UsesTheJdkOnly.class);
        RuleAssertions.assertViolated(rule, LooksUpAClassByName.class);
        ReflectionExceptions byName = ReflectionExceptions.builder()
                .annotatedWith(ReflectiveAdapter.class.getName(), "same, by name")
                .build();
        RuleAssertions.assertKept(VidocqArchRules.noReflection(byName, SOURCE), AnnotatedAdapter.class, UsesTheJdkOnly.class);
    }

    @Test
    void anExceptionByClassSparesItsNestedClasses() {
        ReflectionExceptions byClass = ReflectionExceptions.builder()
                .inClass(VidocqArchRulesTest.class.getName(), "the test class stands for an adapter")
                .build();
        RuleAssertions.assertKept(
                VidocqArchRules.noReflection(byClass, SOURCE),
                LooksUpAClassByName.class,
                ReadsAMethodReflectively.class,
                CoreClass.class);
    }

    @Test
    void everyExceptionNeedsItsReason() {
        ReflectionExceptions.Builder builder = ReflectionExceptions.builder();
        assertThrows(IllegalArgumentException.class, () -> builder.inPackage("a..", " "));
        assertThrows(IllegalArgumentException.class, () -> builder.inPackage("a..", null));
        assertThrows(IllegalArgumentException.class, () -> builder.annotatedWith(ReflectiveAdapter.class, ""));
        assertThrows(IllegalArgumentException.class, () -> builder.annotatedWith("a.B", null));
        assertThrows(IllegalArgumentException.class, () -> builder.inClass("a.B", " "));
        assertThrows(IllegalArgumentException.class, () -> builder.inPackage(" ", "why"));
    }

    @Test
    void theRulePrintsTheSourceTheExceptionsAndTheirReasons() {
        ReflectionExceptions exceptions = ReflectionExceptions.builder()
                .inPackage("io.vidocq.x.gen..", "generated code loads classes by name")
                .annotatedWith(ReflectiveAdapter.class, "reads user annotations at run time")
                .inClass("io.vidocq.x.Boot", "loads the build-time index")
                .build();
        String description = VidocqArchRules.noReflection(exceptions, SOURCE).getDescription();
        assertTrue(description.contains(SOURCE), description);
        assertTrue(description.contains("io.vidocq.x.gen.."), description);
        assertTrue(description.contains("generated code loads classes by name"), description);
        assertTrue(description.contains("@ReflectiveAdapter"), description);
        assertTrue(description.contains("reads user annotations at run time"), description);
        assertTrue(description.contains("io.vidocq.x.Boot"), description);
        assertTrue(description.contains("loads the build-time index"), description);
    }

    @Test
    void aNewCallOutsideAnExceptionFailsWithAReadableMessage() {
        ReflectionExceptions exceptions = ReflectionExceptions.builder()
                .inPackage("io.vidocq.x.gen..", "generated code loads classes by name")
                .build();
        ArchRule rule = VidocqArchRules.noReflection(exceptions, SOURCE);
        AssertionError error = assertThrows(AssertionError.class, () -> RuleAssertions.assertKept(rule, LooksUpAClassByName.class));
        String message = error.getMessage();
        assertTrue(message.contains("LooksUpAClassByName"), message);
        assertTrue(message.contains("Class.forName"), message);
        assertTrue(message.contains(SOURCE), message);
        assertTrue(message.contains("ReflectionExceptions"), message);
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
        assertTrue(List.of(Jdk.any()).contains("java.."));
    }

    @Test
    void theJdkConstantsOfTheBricksEqualTheMethods() {
        assertTrue(java.util.Arrays.equals(Jdk.ANY, Jdk.any()));
        assertTrue(java.util.Arrays.equals(Jdk.RUNTIME, Jdk.runtime()));
        assertTrue(java.util.Arrays.equals(Jdk.COMPILER, Jdk.compiler()));
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

    @Retention(RetentionPolicy.RUNTIME)
    @interface ReflectiveAdapter {
    }

    @ReflectiveAdapter
    static final class AnnotatedAdapter {
        Class<?> find(String name) throws ClassNotFoundException {
            return Class.forName(name);
        }

        static final class Inner {
            Object method() throws NoSuchMethodException {
                return String.class.getDeclaredMethod("length");
            }
        }
    }

    static final class HoldsATypeToken {
        Type argument(Type type) {
            return type instanceof ParameterizedType parameterized ? parameterized.getActualTypeArguments()[0] : type;
        }
    }

    static final class InvokesAMethod {
        Object call(java.lang.reflect.Method method, Object target) throws ReflectiveOperationException {
            return method.invoke(target);
        }
    }

    static final class LoadsByTheClassLoader {
        Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
            return loader.loadClass(name);
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
