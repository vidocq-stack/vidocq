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
package io.vidocq.dirac.cdi.internal;

import io.vidocq.dirac.api.DiracException;
import io.vidocq.dirac.cdi.internal.otherpackage.OtherPackageMeteredBase;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.eclipse.microprofile.metrics.annotation.Timed;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 tests — BCE validation of {@code @Gauge} methods, MicroProfile Metrics 5.1.1 spec §4.2.
 */
class DiracExtensionTest {

    @Test
    void scansValidGaugeMethod() {
        var discovered = new DiscoveredMetrics();
        discovered.scanGaugeMethods(ValidGaugeService.class);

        assertEquals(1, discovered.gauges().size());
        var resolved = discovered.gauges().iterator().next();
        assertEquals("valid.gauge", resolved.metricID().getName());
        assertEquals(MetricRegistry.APPLICATION_SCOPE, resolved.scope());
    }

    @Test
    void rejectsGaugeWithParameters() {
        var discovered = new DiscoveredMetrics();
        assertThrows(DiracException.class, () -> discovered.scanGaugeMethods(InvalidParamGaugeService.class));
    }

    @Test
    void rejectsGaugeReturningVoid() {
        var discovered = new DiscoveredMetrics();
        assertThrows(DiracException.class, () -> discovered.scanGaugeMethods(InvalidVoidGaugeService.class));
    }

    @Test
    void rejectsGaugeReturningNonNumericType() {
        var discovered = new DiscoveredMetrics();
        assertThrows(DiracException.class, () -> discovered.scanGaugeMethods(InvalidTypeGaugeService.class));
    }

    @Test
    void rejectsMalformedGaugeTags() {
        var discovered = new DiscoveredMetrics();
        assertThrows(DiracException.class, () -> discovered.scanGaugeMethods(InvalidTagGaugeService.class));
    }

    /** DRC-007: the same metric discovered twice is kept once (records compare tag contents). */
    @Test
    void ingestingTheSameClassTwiceKeepsOneMetricEach() {
        var discovered = new DiscoveredMetrics();
        discovered.scanTimedMethods(TimedCountedService.class);
        discovered.scanTimedMethods(TimedCountedService.class);
        discovered.scanCountedMethods(TimedCountedService.class);
        discovered.scanCountedMethods(TimedCountedService.class);

        assertEquals(1, discovered.timers().size());
        assertEquals(1, discovered.counters().size());
    }

    /**
     * DRC-012: the containers intercept a default method a bean inherits from an interface through the annotation of
     * the interface's method. The scan names the metric after the interface, as the interceptors do. An abstract, a
     * static, or an overridden method passes nothing on: by the class, by its superclass, or by a more specific
     * interface. The interface alone is no bean, and has no metric.
     */
    @Test
    void scanFindsTheMetricsOfInheritedDefaultMethodsNamedAfterTheirInterface() {
        var discovered = new DiscoveredMetrics();
        discovered.scanTimedMethods(DefaultMeteredBean.class);
        discovered.scanCountedMethods(DefaultMeteredBean.class);
        discovered.scanGaugeMethods(DefaultMeteredBean.class);
        var ofTheInterface = new DiscoveredMetrics();
        ofTheInterface.scanTimedMethods(MeteredContract.class);
        ofTheInterface.scanCountedMethods(MeteredContract.class);
        ofTheInterface.scanGaugeMethods(MeteredContract.class);

        assertEquals(java.util.Set.of(MetricRegistry.name(MeteredContract.class, "timedDefault")),
                names(discovered.timers()));
        assertEquals(java.util.Set.of(MetricRegistry.name(MeteredContract.class, "countedDefault"),
                "sub.wins"), names(discovered.counters()));
        assertEquals(java.util.Set.of("default.gauge"), gaugeNames(discovered));
        assertEquals(DefaultMeteredBean.class, discovered.gauges().iterator().next().beanClass(), "read on the bean");
        assertTrue(ofTheInterface.isEmpty(), "an interface is not a bean");
    }

    /**
     * DRC-011: a subclass inherits the binding of a non-static superclass method it does not override (CDI 4.1,
     * §4.2). The scan names the metric after the declaring class, as the interceptors do; an overridden or a private
     * method passes nothing on. An override through a type argument has another erasure, and still overrides.
     */
    @Test
    void scanFindsTheMetricsOfInheritedMethodsNamedAfterTheirDeclaringClass() {
        var discovered = new DiscoveredMetrics();
        discovered.scanTimedMethods(MeteredChild.class);
        discovered.scanCountedMethods(MeteredChild.class);

        assertEquals(java.util.Set.of(MetricRegistry.name(MeteredGrandparent.class, "timedInherited")),
                names(discovered.timers()));
        assertEquals(java.util.Set.of(MetricRegistry.name(MeteredParent.class, "countedInherited"),
                MetricRegistry.name(MeteredGrandparent.class, "countedFromGrandparent")),
                names(discovered.counters()));
    }

    /**
     * DRC-013: a bean inherits the {@code @Gauge} methods of its superclasses that it does not override, the
     * superclass in the deployment or not. Each gauge is named after the class that declares its method, as the
     * superclass names it when it is scanned on its own, and is read on the bean. A private, a static or an
     * overridden method passes no gauge on.
     */
    @Test
    void scanFindsTheGaugesOfInheritedMethodsNamedAfterTheirDeclaringClass() {
        var discovered = new DiscoveredMetrics();
        discovered.scanGaugeMethods(GaugedChild.class);

        assertEquals(java.util.Set.of(MetricRegistry.name(GaugedGrandparent.class, "fromGrandparent"),
                        "gauged.parent", "gauged.child"),
                gaugeNames(discovered));
        for (var gauge : discovered.gauges()) {
            assertEquals(GaugedChild.class, gauge.beanClass(), gauge.metricID() + " is read on the bean");
        }
    }

    /** DRC-013: a package-private gauge method of a superclass in another package is not inherited (JLS 8.4.8). */
    @Test
    void aPackagePrivateGaugeOfASuperclassInAnotherPackageIsNotInherited() {
        var discovered = new DiscoveredMetrics();
        discovered.scanGaugeMethods(OtherPackageHeir.class);

        assertEquals(java.util.Set.of("otherpackage.publicLevel"), gaugeNames(discovered));
    }

    private static java.util.Set<String> gaugeNames(DiscoveredMetrics discovered) {
        return discovered.gauges().stream().map(g -> g.metricID().getName())
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * DRC-011: a superclass whose method names a type absent at run time (an optional dependency, a
     * {@code requires static} module) cannot be read; the inherited walk stops there, and the bean keeps its own
     * metrics instead of being skipped whole.
     */
    @Test
    void aSuperclassThatCannotBeLinkedKeepsTheBeanOwnMetrics() throws Exception {
        var loader = new HidingLoader(LinkMissing.class.getName(), LinkBase.class.getName(), LinkBean.class.getName());
        Class<?> bean = loader.loadClass(LinkBean.class.getName());
        Class<?> base = bean.getSuperclass();
        assertThrows(NoClassDefFoundError.class, base::getDeclaredMethods);

        var discovered = new DiscoveredMetrics();
        discovered.ingest(bean);

        assertEquals(java.util.Set.of("link.own"), names(discovered.counters()));
    }

    /**
     * DRC-011: the creator of the synthetic bean reports a superclass that cannot be linked at {@code WARNING}, once
     * though the scan reads it for {@code @Timed} and for {@code @Counted}; the extension's pass, which may run at
     * build time, reports it at {@code DEBUG}. So each run of the extension, which registers its own synthetic bean,
     * gives one {@code WARNING}. The extension keeps the class for the creator even when it carries no metric of its
     * own: the metrics it would inherit cannot be read, and the creator registers nothing for it.
     */
    @Test
    void theCreatorReportsASuperclassThatCannotBeLinkedAtWarning() throws Exception {
        for (Class<?> fixture : List.of(LinkBean.class, LinkHeir.class)) {
            var loader = new HidingLoader(LinkMissing.class.getName(), LinkBase.class.getName(), fixture.getName());
            Class<?> bean = loader.loadClass(fixture.getName());
            var thread = Thread.currentThread();
            var previous = thread.getContextClassLoader();
            thread.setContextClassLoader(loader);
            try (var log = new CapturedLog(DiscoveredMetrics.class)) {
                assertTrue(DiracExtension.keepsForTheCreator(bean), fixture.getSimpleName() + " kept for the creator");
                assertEquals(List.of(), log.warnings(), "the extension's pass");

                var classes = new ClassesParameter(List.of(bean.getName()));
                var created = new DiscoveredMetricsCreator().create(null, classes);

                var warnings = log.warnings();
                assertEquals(1, warnings.size(), "one warning from the creator");
                assertEquals(List.of(fixture.getName(), LinkBase.class.getName()),
                        List.of(warnings.getFirst().getParameters()).subList(0, 2));
                assertEquals(fixture == LinkBean.class ? java.util.Set.of("link.own") : java.util.Set.of(),
                        names(created.counters()));
                assertTrue(created.timers().isEmpty() && created.gauges().isEmpty(), "nothing else registered");
            } finally {
                thread.setContextClassLoader(previous);
            }
        }
    }

    /** The {@code classes} parameter the extension gives the creator of the synthetic bean. */
    private record ClassesParameter(List<String> classes) implements Parameters {
        @Override
        public <T> T get(String key, Class<T> type) {
            return type.cast(classes.toArray(String[]::new));
        }

        @Override
        public <T> T get(String key, Class<T> type, T defaultValue) {
            return get(key, type);
        }
    }

    /**
     * DRC-011: a package-private method of a superclass in another package is not inherited (JLS 8.4.8), so no
     * subclass of the bean's package intercepts it: no metric for it, whether the bean declares a method of the same
     * signature or not.
     */
    @Test
    void aPackagePrivateMethodOfASuperclassInAnotherPackageRegistersNoMetric() {
        for (Class<?> bean : List.of(OtherPackageHeir.class, OtherPackageRedeclarer.class)) {
            var discovered = new DiscoveredMetrics();
            discovered.scanCountedMethods(bean);

            assertEquals(java.util.Set.of("otherpackage.public"), names(discovered.counters()), bean.getSimpleName());
        }
    }

    /** Defines {@code own} from the test's class files, and hides {@code hidden}: as if it were absent at run time. */
    private static final class HidingLoader extends ClassLoader {
        private final String hidden;
        private final java.util.Set<String> own;

        HidingLoader(String hidden, String... own) {
            super(DiracExtensionTest.class.getClassLoader());
            this.hidden = hidden;
            this.own = java.util.Set.of(own);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(hidden)) {
                throw new ClassNotFoundException(name);
            }
            if (!own.contains(name)) {
                return super.loadClass(name, resolve);
            }
            var loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
            try (var in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                byte[] bytes = java.util.Objects.requireNonNull(in, name).readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (java.io.IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

    private static java.util.Set<String> names(java.util.Set<DiracExtension.PreRegisteredMetric> metrics) {
        var names = new java.util.HashSet<String>();
        metrics.forEach(metric -> names.add(metric.metadata().getName()));
        return names;
    }

    @Test
    void preRegisteredMetricComparesTagContents() {
        var metadata = Metadata.builder().withName("m").build();
        var first = new DiracExtension.PreRegisteredMetric(metadata, List.of(new Tag("k", "v")), "application");
        var same = new DiracExtension.PreRegisteredMetric(metadata, List.of(new Tag("k", "v")), "application");
        var otherTags = new DiracExtension.PreRegisteredMetric(metadata, List.of(new Tag("k", "w")), "application");
        var otherScope = new DiracExtension.PreRegisteredMetric(metadata, List.of(new Tag("k", "v")), "vendor");

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, otherTags);
        assertNotEquals(first, otherScope);
        assertNotEquals(first, new DiracExtension.PreRegisteredMetric(Metadata.builder().withName("n").build(),
                List.of(new Tag("k", "v")), "application"));
        assertNotEquals(first, (Object) "m");
        assertTrue(first.toString().contains("tags=[Tag{k=v}]"), first.toString());
    }

    @Test
    void resolvedGaugeComparesTagContents() {
        var metadata = Metadata.builder().withName("g").build();
        Function<Object, Number> invoker = bean -> 1;
        var id = new MetricID("g", new Tag("k", "v"));
        var first = new DiracExtension.ResolvedGauge(Object.class, null, true, id, metadata,
                List.of(new Tag("k", "v")), "application", invoker);
        var same = new DiracExtension.ResolvedGauge(Object.class, null, true, id, metadata,
                List.of(new Tag("k", "v")), "application", invoker);
        var otherTags = new DiracExtension.ResolvedGauge(Object.class, null, true, id, metadata,
                List.of(new Tag("k", "w")), "application", invoker);
        var otherBean = new DiracExtension.ResolvedGauge(String.class, null, true, id, metadata,
                List.of(new Tag("k", "v")), "application", invoker);
        var instanceGauge = new DiracExtension.ResolvedGauge(Object.class, null, false, id, metadata,
                List.of(new Tag("k", "v")), "application", invoker);

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, otherTags);
        assertNotEquals(first, otherBean);
        assertNotEquals(first, instanceGauge);
        assertNotEquals(first, gauge(MethodHandles.zero(int.class), id, metadata, "application", invoker));
        assertNotEquals(first, gauge(null, new MetricID("h"), metadata, "application", invoker));
        assertNotEquals(first, gauge(null, id, Metadata.builder().withName("h").build(), "application", invoker));
        assertNotEquals(first, gauge(null, id, metadata, "vendor", invoker));
        assertNotEquals(first, gauge(null, id, metadata, "application", bean -> 2));
        assertNotEquals(first, (Object) "g");
        assertTrue(first.toString().contains("tags=[Tag{k=v}]"), first.toString());
    }

    private static DiracExtension.ResolvedGauge gauge(MethodHandle handle, MetricID id, Metadata metadata,
                                                      String scope, Function<Object, Number> invoker) {
        return new DiracExtension.ResolvedGauge(Object.class, handle, true, id, metadata,
                List.of(new Tag("k", "v")), scope, invoker);
    }

    static class TimedCountedService {
        @Timed(name = "twice.timer", absolute = true, tags = {"kind=unit"})
        @Counted(name = "twice.counter", absolute = true, tags = {"kind=unit"})
        void work() {
            // metric target only
        }
    }

    static class MeteredGrandparent<T> {
        @Timed
        public void timedInherited() {
            // inherited by MeteredChild through MeteredParent
        }

        @Counted
        public void countedFromGrandparent() {
            // inherited by MeteredChild through MeteredParent
        }

        @Counted
        public void overriddenByTheParent() {
            // MeteredParent overrides it without the annotation
        }

        @Counted
        public void overriddenThroughATypeArgument(T value) {
            // MeteredParent overrides it as overriddenThroughATypeArgument(String): another erasure
        }
    }

    static class MeteredParent extends MeteredGrandparent<String> {
        @Counted
        public void countedInherited() {
            // inherited by MeteredChild
        }

        @Counted
        public void overriddenByTheChild() {
            // MeteredChild overrides it without the annotation
        }

        @Counted
        private void hidden() {
            // private: not inherited
        }

        @Counted
        public static void shared() {
            // static: not an interceptor binding target
        }

        @Override
        public void overriddenByTheParent() {
            hidden();
        }

        @Override
        public void overriddenThroughATypeArgument(String value) {
            // overrides the grandparent's method through its type argument, without the annotation
        }
    }

    public interface MeteredContract {
        @Timed
        default void timedDefault() {
            // timed through the bean
        }

        @Counted
        default void countedDefault() {
            // counted through the bean
        }

        @Counted(name = "abstract", absolute = true)
        void abstractOne();

        @Counted(name = "static", absolute = true)
        static void staticOne() {
            // not inherited
        }

        @Counted(name = "overridden.by.the.superclass", absolute = true)
        default void overriddenByTheSuperclass() {
            // overridden
        }

        @Counted(name = "overridden.by.the.bean", absolute = true)
        default void overriddenByTheBean() {
            // overridden
        }

        @Counted(name = "base.loses", absolute = true)
        default void overriddenBySubInterface() {
            // overridden by MeteredSubContract
        }

        @Gauge(name = "default.gauge", absolute = true, unit = MetricUnits.NONE)
        default long level() {
            return 3L;
        }

        @Gauge(name = "static.gauge", absolute = true, unit = MetricUnits.NONE)
        static long sharedLevel() {
            return 4L;
        }
    }

    public interface MeteredSubContract extends MeteredContract {
        @Override
        @Counted(name = "sub.wins", absolute = true)
        default void overriddenBySubInterface() {
            // the most specific default method
        }
    }

    public static class DefaultMeteredParent implements MeteredContract {
        @Override
        public void abstractOne() {
            // implements the abstract method, without a binding
        }

        @Override
        public void overriddenByTheSuperclass() {
            // no binding
        }
    }

    public static class DefaultMeteredBean extends DefaultMeteredParent implements MeteredContract, MeteredSubContract {
        @Override
        public void overriddenByTheBean() {
            // no binding
        }
    }

    static class MeteredChild extends MeteredParent {
        @Override
        public void overriddenByTheChild() {
            // not counted: the override does not carry the annotation
        }
    }

    static class OtherPackageHeir extends OtherPackageMeteredBase {
    }

    static class GaugedGrandparent {
        @Gauge(unit = MetricUnits.NONE)
        public long fromGrandparent() {
            return 1;
        }

        @Gauge(name = "gauged.overridden", absolute = true, unit = MetricUnits.NONE)
        public long overriddenBelow() {
            return 2;
        }

        @Gauge(name = "gauged.static", absolute = true, unit = MetricUnits.NONE)
        public static long shared() {
            return 3;
        }

        @Gauge(name = "gauged.private", absolute = true, unit = MetricUnits.NONE)
        private long hidden() {
            return 4;
        }

        long readHidden() {
            return hidden();
        }
    }

    static class GaugedParent extends GaugedGrandparent {
        @Override
        public long overriddenBelow() {
            // overrides the grandparent's gauge method without the annotation: no gauge
            return 5;
        }

        @Gauge(name = "gauged.parent", absolute = true, unit = MetricUnits.NONE)
        protected long fromParent() {
            return 6;
        }
    }

    static class GaugedChild extends GaugedParent {
        @Gauge(name = "gauged.child", absolute = true, unit = MetricUnits.NONE)
        public long own() {
            return 7;
        }
    }

    static class OtherPackageRedeclarer extends OtherPackageMeteredBase {
        void packagePrivate() {
            // a method of its own: the base's is not inherited from another package, so this overrides nothing
        }
    }

    static class LinkMissing {
    }

    public static class LinkBase {
        public void optional(LinkMissing missing) {
            // names a type the HidingLoader hides
        }

        @Counted(name = "link.inherited", absolute = true)
        public void inherited() {
            // unreadable: getDeclaredMethods of this class fails
        }
    }

    public static class LinkBean extends LinkBase {
        @Counted(name = "link.own", absolute = true)
        public void own() {
            // the bean's own metric
        }
    }

    /** No metric of its own: those it would inherit sit in a superclass that cannot be linked. */
    public static class LinkHeir extends LinkBase {
    }

    static class ValidGaugeService {
        @Gauge(name = "valid.gauge", absolute = true, tags = {"kind=unit"}, unit = MetricUnits.NONE)
        Integer current() {
            return 1;
        }
    }

    static class InvalidParamGaugeService {
        @Gauge(unit = MetricUnits.NONE)
        Integer current(int ignored) {
            return ignored;
        }
    }

    static class InvalidVoidGaugeService {
        @Gauge(unit = MetricUnits.NONE)
        void current() {
            // invalid gauge: returns nothing
        }
    }

    static class InvalidTypeGaugeService {
        @Gauge(unit = MetricUnits.NONE)
        String current() {
            return "bad";
        }
    }

    static class InvalidTagGaugeService {
        @Gauge(tags = {"missing-separator"}, unit = MetricUnits.NONE)
        Integer current() {
            return 1;
        }
    }
}


