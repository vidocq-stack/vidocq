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
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static io.vidocq.dirac.cdi.internal.TestInvocationContext.construction;
import static io.vidocq.dirac.cdi.internal.TestInvocationContext.invocation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Counted} metric resolution, MicroProfile Metrics 5.1.1 §5.2 (annotations) and §5.3
 * (metric names): class-level and constructor-level annotations, inherited and
 * meta-annotated methods, container subclasses ({@code $$}) and super-call methods.
 */
class CountedInterceptorResolutionTest {

    private static final String PKG = CountedInterceptorResolutionTest.class.getPackageName();
    private static final String RESULT = "done";

    private final MetricRegistryProducerBean registries = new MetricRegistryProducerBean();
    private final CountedInterceptor interceptor = new CountedInterceptor(registries);

    private long count(String name, Tag... tags) {
        var counter = registries.applicationRegistry().getCounter(new MetricID(name, tags));
        assertNotNull(counter, name);
        return counter.getCount();
    }

    @Test
    void classLevelAnnotationCountsConstructorAndPreRegistersMethods() throws Exception {
        var context = construction(new ClassCountedBean(), ClassCountedBean.class.getConstructor());

        interceptor.aroundConstruct(context);

        assertEquals(1, context.proceedCount());
        var layer = new Tag("layer", "svc");
        assertEquals(1, count(PKG + ".classy.ClassCountedBean", layer));
        assertEquals(0, count(PKG + ".classy.run", layer));
        assertNull(registries.applicationRegistry().getCounter(new MetricID(PKG + ".classy.hidden", layer)));
        assertNull(registries.applicationRegistry().getCounter(new MetricID(PKG + ".classy.util", layer)));
    }

    @Test
    void unnamedClassLevelAnnotationUsesTheBeanClassName() throws Exception {
        interceptor.aroundConstruct(construction(new UnnamedClassCountedBean(),
                UnnamedClassCountedBean.class.getDeclaredConstructor()));

        assertEquals(1, count(MetricRegistry.name(UnnamedClassCountedBean.class, "UnnamedClassCountedBean")));
        assertEquals(0, count(MetricRegistry.name(UnnamedClassCountedBean.class, "go")));
    }

    @Test
    void constructorLevelAnnotationNamesTheConstructorMetric() throws Exception {
        interceptor.aroundConstruct(construction(null, CtorCountedBean.class.getDeclaredConstructor()));
        interceptor.aroundConstruct(construction(null, CtorCountedBean.class.getDeclaredConstructor(int.class)));
        interceptor.aroundConstruct(construction(null, CtorCountedBean.class.getDeclaredConstructor(String.class)));
        interceptor.aroundConstruct(construction(null, CtorCountedBean.class.getDeclaredConstructor(long.class)));

        assertEquals(1, count("ctor.counter"));
        assertEquals(1, count(MetricRegistry.name(CtorCountedBean.class, "CtorCountedBean")));
        assertEquals(1, count("CtorCountedBean"));
        assertEquals(1, count(MetricRegistry.name(CtorCountedBean.class, "relative")));
    }

    @Test
    void containerSubclassIsResolvedToTheBeanClass() throws Exception {
        interceptor.aroundConstruct(construction(new UnnamedClassCountedBean$$Sub(),
                UnnamedClassCountedBean$$Sub.class.getDeclaredConstructor()));

        assertEquals(1, count(MetricRegistry.name(UnnamedClassCountedBean.class, "UnnamedClassCountedBean")));
        assertEquals(0, count(MetricRegistry.name(UnnamedClassCountedBean.class, "go")));
    }

    @Test
    void privateConstructorIsNotCountedByAClassLevelAnnotation() throws Exception {
        var context = construction(null, PrivateCtorBean.class.getDeclaredConstructor());

        interceptor.aroundConstruct(context);

        assertEquals(1, context.proceedCount());
        assertNull(registries.applicationRegistry().getCounter(new MetricID("priv.PrivateCtorBean")));
    }

    @Test
    void unannotatedConstructorOnlyProceeds() throws Exception {
        var context = construction(new PlainBean(), PlainBean.class.getDeclaredConstructor());

        interceptor.aroundConstruct(context);

        assertEquals(1, context.proceedCount());
        assertEquals(0, count("plain"));
    }

    @Test
    void unannotatedMethodOnlyProceeds() throws Exception {
        var context = invocation(new PlainBean(), PlainBean.class.getDeclaredMethod("untimed"), () -> RESULT);

        assertEquals(RESULT, interceptor.aroundInvoke(context));
        assertTrue(registries.applicationRegistry().getCounters().isEmpty());
    }

    @Test
    void methodAnnotationIsFoundOnTheOverriddenMethod() throws Exception {
        interceptor.preRegisterCounters(DerivedBean.class);

        interceptor.aroundInvoke(invocation(new DerivedBean(), DerivedBean.class.getDeclaredMethod("work"), () -> RESULT));

        assertEquals(1, count(MetricRegistry.name(BaseBean.class, "base.work")));
        assertEquals(0, count("helper"));
    }

    @Test
    void superCallMethodIsCountedOnce() throws Exception {
        interceptor.preRegisterCounters(DerivedBean.class);
        var superCall = DerivedBean$$Proxy.class.getDeclaredMethod("work$$super$work");

        interceptor.aroundInvoke(invocation(new DerivedBean$$Proxy(), superCall, () -> RESULT));

        assertEquals(1, count(MetricRegistry.name(BaseBean.class, "base.work")));
        assertEquals(0, count("helper"));
    }

    @Test
    void superCallOnABeanWithoutSuperclassOnlyCountsTheMethod() throws Exception {
        interceptor.preRegisterCounters(PlainBean.class);
        var superCall = PlainBean$$Proxy.class.getDeclaredMethod("run$$super$run");

        assertEquals(RESULT, interceptor.aroundInvoke(invocation(new PlainBean$$Proxy(), superCall, () -> RESULT)));
        assertEquals(1, count("plain"));
    }

    @Test
    void superCallToAnUnknownMethodIsNotCounted() throws Exception {
        var superCall = PlainBean$$Proxy.class.getDeclaredMethod("gone$$super$missing");

        assertEquals(RESULT, interceptor.aroundInvoke(invocation(new PlainBean$$Proxy(), superCall, () -> RESULT)));
        assertTrue(registries.applicationRegistry().getCounters().isEmpty());
    }

    @Test
    void metaAnnotationIsHonoured() throws Exception {
        // the DiscoveredMetrics bean registers meta-annotated metrics at startup
        registries.applicationRegistry().counter("meta");

        interceptor.aroundInvoke(invocation(null, MetaBean.class.getDeclaredMethod("run"), () -> RESULT));

        assertEquals(1, count("meta"));
    }

    @Test
    void counterRemovedFromTheRegistryIsReported() throws Exception {
        interceptor.preRegisterCounters(PlainBean.class);
        registries.applicationRegistry().remove("plain");
        var context = invocation(new PlainBean(), PlainBean.class.getDeclaredMethod("run"), () -> RESULT);

        assertThrows(IllegalStateException.class, () -> interceptor.aroundInvoke(context));
    }

    @Test
    void missingMethodIsRejected() {
        var context = invocation(new PlainBean(), null, () -> RESULT);

        assertThrows(NullPointerException.class, () -> interceptor.aroundInvoke(context));
    }

    @Test
    void reservedOrIncompleteTagsAreRejected() throws Exception {
        var reserved = invocation(null, BadTagsBean.class.getDeclaredMethod("reserved"), () -> RESULT);
        var noValue = invocation(null, BadTagsBean.class.getDeclaredMethod("noValue"), () -> RESULT);

        assertThrows(DiracException.class, () -> interceptor.aroundInvoke(reserved));
        assertThrows(DiracException.class, () -> interceptor.aroundInvoke(noValue));
    }

    @Test
    void absoluteUnnamedMethodUsesTheMethodNameInTheDefaultScope() throws Exception {
        interceptor.preRegisterCounters(AbsoluteBean.class);

        interceptor.aroundInvoke(invocation(null, AbsoluteBean.class.getDeclaredMethod("bare"), () -> RESULT));

        assertEquals(1, count("bare"));
    }

    @Counted(name = "classy", tags = {"layer=svc", " "})
    public static class ClassCountedBean {
        public ClassCountedBean() {
            // metric target only
        }

        public void run() {
            // metric target only
        }

        private void hidden() {
            // metric target only
        }

        static void util() {
            // metric target only
        }
    }

    @Counted
    static class UnnamedClassCountedBean {
        void go() {
            // metric target only
        }
    }

    static class UnnamedClassCountedBean$$Sub extends UnnamedClassCountedBean {
    }

    static class CtorCountedBean {
        @Counted(name = "ctor.counter", absolute = true)
        CtorCountedBean() {
        }

        @Counted
        CtorCountedBean(int ignoredValue) {
        }

        @Counted(absolute = true)
        CtorCountedBean(String ignoredValue) {
        }

        @Counted(name = "relative")
        CtorCountedBean(long ignoredValue) {
        }
    }

    @Counted(name = "priv", absolute = true)
    static class PrivateCtorBean {
        private PrivateCtorBean() {
        }
    }

    static class PlainBean {
        @Counted(name = "plain", absolute = true)
        public void run() {
            // metric target only
        }

        void untimed() {
            // not a metric
        }
    }

    static class PlainBean$$Proxy extends PlainBean {
        public void run$$super$run() {
            run();
        }

        public void gone$$super$missing() {
            // super-call to a method the bean does not declare
        }
    }

    static class BaseBean {
        @Counted(name = "base.work")
        public void work() {
            // metric target only
        }

        @Counted(name = "helper", absolute = true)
        void helper() {
            // metric target only
        }
    }

    static class DerivedBean extends BaseBean {
        @Override
        public void work() {
            // overrides without the annotation
        }
    }

    static class DerivedBean$$Proxy extends DerivedBean {
        public void work$$super$work() {
            work();
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Counted(name = "meta", absolute = true)
    @interface MetaCounted {
    }

    static class MetaBean {
        @MetaCounted
        public void run() {
            // metric target only
        }
    }

    static class BadTagsBean {
        @Counted(tags = {"mp_scope=x"})
        void reserved() {
            // metric target only
        }

        @Counted(tags = {"k="})
        void noValue() {
            // metric target only
        }
    }

    static class AbsoluteBean {
        @Counted(absolute = true, scope = " ")
        void bare() {
            // metric target only
        }
    }
}
