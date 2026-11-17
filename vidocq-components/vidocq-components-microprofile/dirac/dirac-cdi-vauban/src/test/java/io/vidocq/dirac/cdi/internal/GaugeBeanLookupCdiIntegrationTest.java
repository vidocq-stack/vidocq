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
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DRC-015: the gauge of a bean whose class has a bounded type variable is read on the bean of that class.
 * The raw class of such a bean is not one of the types it can be looked up by (CDI 4.1 §5.2.4). Nothing else
 * changes: the lookup still asks for {@code @Default}, and a class without such a bean is still unsatisfied.
 */
class GaugeBeanLookupCdiIntegrationTest {

    @Test
    void theGaugeOfABoundedGenericBeanIsRead() {
        try (SeContainer container = container(BoundedLevel.class)) {
            assertEquals(3L, gauge(container, "lookup.bounded").getValue().longValue());
        }
    }

    @Test
    void theBoundedGenericBeanOfAGaugeIsCreatedOnceAndDestroyedWithTheContainer() {
        CountedBoundedLevel.CREATED.set(0);
        CountedBoundedLevel.DESTROYED.set(0);
        int created;
        int destroyedWhileOpen;
        try (SeContainer container = container(CountedBoundedLevel.class)) {
            Gauge<?> gauge = gauge(container, "lookup.counted.bounded");
            gauge.getValue();
            gauge.getValue();
            gauge.getValue();
            created = CountedBoundedLevel.CREATED.get();
            destroyedWhileOpen = CountedBoundedLevel.DESTROYED.get();
        }
        int destroyedOnceClosed = CountedBoundedLevel.DESTROYED.get();

        assertAll(
                () -> assertEquals(1, created, "instances created by three reads"),
                () -> assertEquals(0, destroyedWhileOpen, "instances destroyed while the container is open"),
                () -> assertEquals(1, destroyedOnceClosed, "instances destroyed once the container is closed"));
    }

    /**
     * Creating the target runs application code, which may read the same gauge: the read that ends first keeps its
     * instance, and the other instance is destroyed at once.
     */
    @Test
    void aBoundedGenericBeanThatReadsItsOwnGaugeWhileItIsCreatedLeavesOneInstance() {
        ReentrantLevel.CREATED.set(0);
        ReentrantLevel.DESTROYED.set(0);
        long value;
        int created;
        int destroyedWhileOpen;
        try (SeContainer container = container(ReentrantLevel.class)) {
            Gauge<?> gauge = gauge(container, "lookup.reentrant");
            value = gauge.getValue().longValue();
            gauge.getValue();
            created = ReentrantLevel.CREATED.get();
            destroyedWhileOpen = ReentrantLevel.DESTROYED.get();
        }
        int destroyedOnceClosed = ReentrantLevel.DESTROYED.get();

        assertAll(
                () -> assertEquals(4L, value),
                () -> assertEquals(2, created, "instances created by two reads, the first one reentrant"),
                () -> assertEquals(1, destroyedWhileOpen, "instances destroyed while the container is open"),
                () -> assertEquals(2, destroyedOnceClosed, "instances destroyed once the container is closed"));
    }

    /**
     * A regression guard, green before and after DRC-015: a class whose bean is not enabled has no target, and each
     * read fails as it did.
     */
    @Test
    void theGaugeOfABoundedGenericClassWithoutABeanIsUnsatisfied() {
        try (SeContainer container = container(DisabledBoundedLevel.class)) {
            Gauge<?> gauge = gauge(container, "lookup.disabled");

            DiracException failure = assertThrows(DiracException.class, gauge::getValue);

            assertInstanceOf(UnsatisfiedResolutionException.class, failure.getCause());
        }
    }

    /**
     * A producer is a bean of the class that declares it. Here it is the only one with {@code @Default}: it is not
     * the bean of the class, so a read of the gauge never runs it, and each read fails as it did.
     */
    @Test
    void whatAProducerOfABoundedGenericClassMakesIsNotTheTargetOfItsGauge() {
        Marker.CREATED.set(0);
        try (SeContainer container = container(TaggedBoundedLevelWithProducer.class)) {
            Gauge<?> gauge = gauge(container, "lookup.tagged.bounded");

            DiracException first = assertThrows(DiracException.class, gauge::getValue);
            DiracException second = assertThrows(DiracException.class, gauge::getValue);

            assertAll(
                    () -> assertInstanceOf(UnsatisfiedResolutionException.class, first.getCause()),
                    () -> assertInstanceOf(UnsatisfiedResolutionException.class, second.getCause()),
                    () -> assertEquals(0, Marker.CREATED.get(), "objects the producer made for two reads"));
        }
    }

    /**
     * {@code @Typed} limits the bean types of a generic class to an interface: the class itself is not a type of its
     * bean, so the gauge has no target, as before DRC-015.
     */
    @Test
    void theGaugeOfATypedBoundedGenericClassIsUnsatisfied() {
        try (SeContainer container = container(TypedBoundedLevel.class)) {
            Gauge<?> gauge = gauge(container, "lookup.typed.bounded");

            DiracException failure = assertThrows(DiracException.class, gauge::getValue);

            assertInstanceOf(UnsatisfiedResolutionException.class, failure.getCause());
        }
    }

    /**
     * The decision is taken once, a failed one too: a bean of the class whose creation fails is not created again at
     * the next read, and each read fails with the cause of the first one. Vauban may call the constructor more than
     * once in one creation: the test counts the calls of the second read.
     */
    @Test
    void aBoundedGenericBeanWhoseCreationFailsIsNotTriedAgain() {
        FailingBoundedLevel.ATTEMPTS.set(0);
        try (SeContainer container = container(FailingBoundedLevel.class)) {
            Gauge<?> gauge = gauge(container, "lookup.failing.bounded");

            DiracException first = assertThrows(DiracException.class, gauge::getValue);
            int triedByTheFirstRead = FailingBoundedLevel.ATTEMPTS.get();
            DiracException second = assertThrows(DiracException.class, gauge::getValue);
            int triedByTheSecondRead = FailingBoundedLevel.ATTEMPTS.get() - triedByTheFirstRead;

            assertAll(
                    () -> assertEquals(FailingBoundedLevel.FAILURE, rootCause(first).getMessage()),
                    () -> assertEquals(FailingBoundedLevel.FAILURE, rootCause(second).getMessage()),
                    () -> assertEquals(0, triedByTheSecondRead, "constructor calls of the second read"));
        }
    }

    /** A read once the container is closed creates no instance that nothing would release. */
    @Test
    void aGaugeReadOnceTheContainerIsClosedCreatesNoInstance() {
        LateBoundedLevel.CREATED.set(0);
        Gauge<?> gauge;
        try (SeContainer container = container(LateBoundedLevel.class)) {
            gauge = gauge(container, "lookup.late.bounded");
        }

        assertThrows(RuntimeException.class, gauge::getValue);

        assertEquals(0, LateBoundedLevel.CREATED.get(), "instances created by a read after the close");
    }

    /**
     * DRC-016, open: a lookup by type creates a new instance of a {@code @Dependent} bean at each read of
     * its gauge.
     */
    @Test
    @Disabled("DRC-016: three reads of the gauge of a @Dependent bean create three instances of it")
    void aDependentBeanIsCreatedOnceForItsGauge() {
        CountedLevel.CREATED.set(0);
        try (SeContainer container = container(CountedLevel.class)) {
            Gauge<?> gauge = gauge(container, "lookup.counted");
            gauge.getValue();
            gauge.getValue();
            gauge.getValue();

            assertEquals(1, CountedLevel.CREATED.get(), "instances created by three reads");
        }
    }

    /** DRC-017, open: the lookup of a gauge's bean names no qualifier, so it asks for {@code @Default}. */
    @Test
    @Disabled("DRC-017: the gauge of a bean that has only another qualifier than @Default cannot be read")
    void theGaugeOfABeanWithAnotherQualifierIsRead() {
        try (SeContainer container = container(TaggedLevel.class)) {
            assertEquals(5L, gauge(container, "lookup.tagged").getValue().longValue());
        }
    }

    /**
     * DRC-018: the bean of the class and the producer it declares both have {@code @Default}; only the bean of the
     * class has a parameterized type of that class, and the gauge is read on it, without running the producer.
     */
    @Test
    void theGaugeOfABoundedGenericBeanThatDeclaresAProducerIsRead() {
        Marker.CREATED.set(0);
        try (SeContainer container = container(BoundedLevelWithProducer.class)) {
            assertAll(
                    () -> assertEquals(8L, gauge(container, "lookup.bounded.producer").getValue().longValue()),
                    () -> assertEquals(0, Marker.CREATED.get(), "objects the producer made for a read"));
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static SeContainer container(Class<?> gaugeBean) {
        // DiracExtension discovers the class itself, in this container (dirac#23).
        return SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(DiracExtension.class, MetricRegistryProducerBean.class, GaugeRegistrationBean.class,
                        gaugeBean)
                .initialize();
    }

    private static Gauge<?> gauge(SeContainer container, String name) {
        return container.select(MetricRegistryProducerBean.class).get().applicationRegistry()
                .getGauge(new MetricID(name));
    }

    /** A bean class with a bounded type variable: necessarily {@code @Dependent} (CDI 4.1, managed beans). */
    @Dependent
    static class BoundedLevel<T extends Number> {

        @org.eclipse.microprofile.metrics.annotation.Gauge(name = "lookup.bounded", absolute = true,
                unit = MetricUnits.NONE)
        public long level() {
            return 3;
        }
    }

    /** Counts its instances: the gauge owns the one it reads. */
    @Dependent
    static class CountedBoundedLevel<T extends Number> {

        static final AtomicInteger CREATED = new AtomicInteger();
        static final AtomicInteger DESTROYED = new AtomicInteger();

        CountedBoundedLevel() {
            CREATED.incrementAndGet();
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.incrementAndGet();
        }

        @org.eclipse.microprofile.metrics.annotation.Gauge(name = "lookup.counted.bounded", absolute = true,
                unit = MetricUnits.NONE)
        public long level() {
            return 1;
        }
    }

    /** Reads its own gauge while the first of its instances is created. */
    @Dependent
    static class ReentrantLevel<T extends Number> {

        static final AtomicInteger CREATED = new AtomicInteger();
        static final AtomicInteger DESTROYED = new AtomicInteger();

        @Inject
        ReentrantLevel(MetricRegistryProducerBean registries) {
            if (CREATED.incrementAndGet() == 1) {
                registries.applicationRegistry().getGauge(new MetricID("lookup.reentrant")).getValue();
            }
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.incrementAndGet();
        }

        @org.eclipse.microprofile.metrics.annotation.Gauge(name = "lookup.reentrant", absolute = true,
                unit = MetricUnits.NONE)
        public long level() {
            return 4;
        }
    }

    /** An alternative that nothing selects: its class is discovered, and it has no enabled bean. */
    @Alternative
    @Dependent
    static class DisabledBoundedLevel<T extends Number> {

        @org.eclipse.microprofile.metrics.annotation.Gauge(name = "lookup.disabled", absolute = true,
                unit = MetricUnits.NONE)
        public long level() {
            return 9;
        }
    }

    /** Its own bean has no {@code @Default}: the only bean of this class that has it is its producer. */
    @Tagged
    @Dependent
    static class TaggedBoundedLevelWithProducer<T extends Number> {

        @Produces
        Marker marker() {
            return new Marker();
        }

        @org.eclipse.microprofile.metrics.annotation.Gauge(name = "lookup.tagged.bounded", absolute = true,
                unit = MetricUnits.NONE)
        public long level() {
            return 6;
        }
    }

    /** What {@link TypedBoundedLevel} is looked up by. */
    interface LevelView {
    }

    /** Its bean types are {@link LevelView} and {@code Object}: no type of this class. */
    @Typed(LevelView.class)
    @Dependent
    static class TypedBoundedLevel<T extends Number> implements LevelView {

        @org.eclipse.microprofile.metrics.annotation.Gauge(name = "lookup.typed.bounded", absolute = true,
                unit = MetricUnits.NONE)
        public long level() {
            return 10;
        }
    }

    /** Its constructor fails, and counts how often it is tried. */
    @Dependent
    static class FailingBoundedLevel<T extends Number> {

        static final String FAILURE = "the level cannot be created";
        static final AtomicInteger ATTEMPTS = new AtomicInteger();

        FailingBoundedLevel() {
            ATTEMPTS.incrementAndGet();
            throw new IllegalStateException(FAILURE);
        }

        @org.eclipse.microprofile.metrics.annotation.Gauge(name = "lookup.failing.bounded", absolute = true,
                unit = MetricUnits.NONE)
        public long level() {
            return 11;
        }
    }

    /** Counts its instances: its gauge is read once the container is closed. */
    @Dependent
    static class LateBoundedLevel<T extends Number> {

        static final AtomicInteger CREATED = new AtomicInteger();

        LateBoundedLevel() {
            CREATED.incrementAndGet();
        }

        @org.eclipse.microprofile.metrics.annotation.Gauge(name = "lookup.late.bounded", absolute = true,
                unit = MetricUnits.NONE)
        public long level() {
            return 12;
        }
    }

    /** Two beans of this class have {@code @Default}: its own and its producer. */
    @Dependent
    static class BoundedLevelWithProducer<T extends Number> {

        @Produces
        Marker marker() {
            return new Marker();
        }

        @org.eclipse.microprofile.metrics.annotation.Gauge(name = "lookup.bounded.producer", absolute = true,
                unit = MetricUnits.NONE)
        public long level() {
            return 8;
        }
    }

    /** What the producers make. */
    static class Marker {

        static final AtomicInteger CREATED = new AtomicInteger();

        Marker() {
            CREATED.incrementAndGet();
        }
    }

    /** A {@code @Dependent} bean that a lookup by type finds. */
    @Dependent
    static class CountedLevel {

        static final AtomicInteger CREATED = new AtomicInteger();

        CountedLevel() {
            CREATED.incrementAndGet();
        }

        @org.eclipse.microprofile.metrics.annotation.Gauge(name = "lookup.counted", absolute = true,
                unit = MetricUnits.NONE)
        public long level() {
            return 1;
        }
    }

    /** A qualifier other than {@code @Default}. */
    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
    @interface Tagged {
    }

    /** A bean that a lookup naming no qualifier does not find. */
    @Tagged
    @ApplicationScoped
    static class TaggedLevel {

        @org.eclipse.microprofile.metrics.annotation.Gauge(name = "lookup.tagged", absolute = true,
                unit = MetricUnits.NONE)
        public long level() {
            return 5;
        }
    }
}
