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

import jakarta.enterprise.inject.spi.Annotated;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.Timer;
import org.eclipse.microprofile.metrics.annotation.Metric;
import org.eclipse.microprofile.metrics.annotation.RegistryScope;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Injection of registries and metrics, MicroProfile Metrics 5.1.1 §2.4 ({@code @RegistryScope},
 * legacy {@code @RegistryType}) and §5.1 ({@code @Metric} injection points: name, absolute,
 * tags and scope).
 */
class MetricRegistryProducerBeanInjectionTest {

    private final MetricRegistryProducerBean bean = new MetricRegistryProducerBean();

    @Test
    void registryScopeQualifierSelectsTheRegistry() throws Exception {
        assertSame(bean.vendorRegistry(), bean.produceByScope(point("vendorRegistry")));
        assertSame(bean.applicationRegistry(), bean.produceByScope(point("blankScopeRegistry")));
        assertSame(bean.applicationRegistry(), bean.produceByScope(point("plainRegistry")));
        assertSame(bean.applicationRegistry(), bean.produceByScope(new FieldInjectionPoint(null, null)));
        assertSame(bean.applicationRegistry(), bean.produceByScope(null));
    }

    @Test
    void customScopeIsCreatedOnDemand() throws Exception {
        var custom = bean.produceByScope(point("customRegistry"));

        assertEquals("custom", custom.getScope());
        assertSame(custom, bean.registry("custom"));
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyRegistryTypesAliasTheScopes() {
        assertSame(bean.applicationRegistry(), bean.produceApplicationByType());
        assertSame(bean.baseRegistry(), bean.produceBaseByType());
        assertSame(bean.vendorRegistry(), bean.produceVendorByType());
    }

    @Test
    void injectedCounterHonoursNameTagsAndScope() throws Exception {
        var counter = bean.produceCounter(point("taggedCounter"));
        counter.inc();

        assertSame(counter, bean.vendorRegistry().getCounter(new MetricID("c", new Tag("a", "b"))));
        assertEquals(1, counter.getCount());
    }

    @Test
    void injectedMetricNamesFollowTheSpecDefaults() throws Exception {
        var unannotated = bean.produceCounter(point("plainCounter"));
        var absolute = bean.produceTimer(point("absoluteTimer"));
        var relative = bean.produceHistogram(point("relativeHistogram"));

        var app = bean.applicationRegistry();
        assertSame(unannotated, app.getCounter(new MetricID(MetricRegistry.name(Fixture.class, "plainCounter"))));
        assertSame(absolute, app.getTimer(new MetricID("absoluteTimer")));
        assertSame(relative, app.getHistogram(new MetricID(MetricRegistry.name(Fixture.class, "rel"))));
    }

    @Test
    void injectedGaugeForwardsToTheRegisteredGauge() throws Exception {
        Gauge<Long> handle = bean.produceGauge(point("lateGauge"));

        assertThrows(IllegalStateException.class, handle::getValue);
        bean.applicationRegistry().gauge("late", () -> 42L);
        assertEquals(42L, handle.getValue());
    }

    private static InjectionPoint point(String fieldName) throws NoSuchFieldException {
        Field field = Fixture.class.getDeclaredField(fieldName);
        return new FieldInjectionPoint(field, new FieldAnnotated(field));
    }

    static class Fixture {
        @RegistryScope(scope = MetricRegistry.VENDOR_SCOPE)
        MetricRegistry vendorRegistry;

        @RegistryScope(scope = " ")
        MetricRegistry blankScopeRegistry;

        @RegistryScope(scope = "custom")
        MetricRegistry customRegistry;

        MetricRegistry plainRegistry;

        @Metric(name = "c", absolute = true, tags = {"a=b", " ", "bad", "x="}, scope = MetricRegistry.VENDOR_SCOPE)
        Counter taggedCounter;

        Counter plainCounter;

        @Metric(absolute = true, scope = " ")
        Timer absoluteTimer;

        @Metric(name = "rel")
        Histogram relativeHistogram;

        @Metric(name = "late", absolute = true)
        Gauge<Long> lateGauge;
    }

    private record FieldInjectionPoint(Field field, Annotated annotated) implements InjectionPoint {
        @Override
        public Type getType() {
            return field.getGenericType();
        }

        @Override
        public Set<Annotation> getQualifiers() {
            return Set.of();
        }

        @Override
        public Bean<?> getBean() {
            return null;
        }

        @Override
        public Member getMember() {
            return field;
        }

        @Override
        public Annotated getAnnotated() {
            return annotated;
        }

        @Override
        public boolean isDelegate() {
            return false;
        }

        @Override
        public boolean isTransient() {
            return false;
        }
    }

    private record FieldAnnotated(Field field) implements Annotated {
        @Override
        public Type getBaseType() {
            return field.getGenericType();
        }

        @Override
        public Set<Type> getTypeClosure() {
            return Set.of(field.getGenericType());
        }

        @Override
        public <T extends Annotation> T getAnnotation(Class<T> annotationType) {
            return field.getAnnotation(annotationType);
        }

        @Override
        public <T extends Annotation> Set<T> getAnnotations(Class<T> annotationType) {
            return new HashSet<>(Arrays.asList(field.getAnnotationsByType(annotationType)));
        }

        @Override
        public Set<Annotation> getAnnotations() {
            return new HashSet<>(Arrays.asList(field.getAnnotations()));
        }

        @Override
        public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
            return field.isAnnotationPresent(annotationType);
        }
    }
}
