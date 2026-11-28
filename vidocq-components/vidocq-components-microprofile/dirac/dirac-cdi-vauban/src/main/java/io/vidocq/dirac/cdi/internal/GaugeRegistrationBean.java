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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.inject.Inject;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Connects the metrics discovered by the BCE to the registries targeted by their scope at CDI startup.
 * Pre-registers gauges, timers, and counters before any method call.
 */
@ApplicationScoped
public class GaugeRegistrationBean {
    private final MetricRegistryProducerBean registries;

    // This container's metrics, synthesised by DiracExtension (dirac#23). An Instance, not a plain
    // injection point: the synthetic bean exists only once the extension ran in this container.
    private final Instance<DiscoveredMetrics> discovered;

    // Gauge beans are looked up in this container, not through CDI.current(), which is ambiguous
    // when several containers run in one JVM.
    private final Instance<Object> beans;

    // The beans of this container: a gauge is read on the bean of its class, whatever its qualifiers (DRC-017), and
    // a class with type variables is not a type its bean can be looked up by (DRC-015).
    private final BeanContainer container;

    // How the target of each gauge class is obtained, decided at the first read of a gauge of that class, a failed
    // decision included.
    private final Map<Class<?>, Lookup> lookups = new ConcurrentHashMap<>();

    // Set when the container destroys this bean: a later read creates no instance it would never release.
    private volatile boolean released;

    private final AtomicBoolean registered = new AtomicBoolean();

    /** Required by CDI for the client proxy of this normal-scoped bean; never used to serve calls. */
    protected GaugeRegistrationBean() {
        this(null, null, null, null);
    }

    @Inject
    public GaugeRegistrationBean(MetricRegistryProducerBean registries, Instance<DiscoveredMetrics> discovered,
                                 Instance<Object> beans, BeanContainer container) {
        this.registries = registries;
        this.discovered = discovered;
        this.beans = beans;
        this.container = container;
    }

    @PostConstruct
    void registerMetrics() {
        doRegister();
    }

    void onApplicationStart(@Observes @Initialized(ApplicationScoped.class) Object ignored) {
        doRegister();
    }

    private void doRegister() {
        if (!registered.compareAndSet(false, true)) {
            return;
        }
        if (discovered.isUnsatisfied()) {
            return;
        }
        // Every run of DiracExtension, the build-time one included, registered its own bean: register their union
        // (BUG-20261030-27 of vidocq).
        DiscoveredMetrics metrics = DiscoveredMetrics.merge(discovered);
        metrics.registerGauges(registries, this::target);
        metrics.registerTimers(registries);
        metrics.registerCounters(registries);
    }

    /** Destroys the targets this bean created itself, when the container destroys it. */
    @PreDestroy
    void releaseOwnedTargets() {
        released = true;
        lookups.values().forEach(Lookup::release);
    }

    /** The instance a gauge of {@code beanClass} is read on. */
    private Object target(Class<?> beanClass) {
        Lookup lookup = lookups.get(beanClass);
        if (lookup == null) {
            if (released) {
                // The container stopped: nothing would release an instance created now.
                return beans.select(beanClass).get();
            }
            // Not computeIfAbsent: creating a target runs application code, which may read a gauge of that class.
            Lookup decided = lookup(beanClass);
            lookup = lookups.putIfAbsent(beanClass, decided);
            if (lookup == null) {
                lookup = decided;
            } else {
                // Another read decided first, and its target is the one that is read.
                decided.release();
            }
            if (released) {
                // The container stopped while this read decided: releaseOwnedTargets may not have seen it.
                decided.release();
            }
        }
        return lookup.target().get();
    }

    /**
     * Decides how the gauges of {@code beanClass} are read: on one reference of its bean, obtained here and kept. For
     * a bean of a normal scope that reference is its client proxy. A {@code @Dependent} bean is created once, here,
     * and destroyed with this bean (DRC-016): each read used to create an instance, which Weld kept until the
     * container stopped. A failure is decided too: each read fails with its cause, and none tries again.
     */
    private Lookup lookup(Class<?> beanClass) {
        Set<Bean<?>> candidates = beansOf(beanClass);
        if (candidates.isEmpty()) {
            // No bean to read the gauge on: each read fails, as the lookup by type says why.
            return new Lookup(() -> beans.select(beanClass).get(), null);
        }
        CreationalContext<?> context = null;
        try {
            Bean<?> bean = container.resolve(candidates);
            context = container.createCreationalContext(bean);
            Type type = Objects.requireNonNullElse(typeOfClass(bean, beanClass), beanClass);
            Object target = container.getReference(bean, type, context);
            return new Lookup(() -> target, context);
        } catch (RuntimeException failure) {
            if (context != null) {
                context.release();
            }
            return new Lookup(() -> {
                throw new IllegalStateException(
                        "The bean of " + beanClass.getName() + " could not be obtained at the first read of its gauge",
                        failure);
            }, null);
        }
    }

    /**
     * The beans a gauge of {@code beanClass} may be read on. First the bean of that class: its bean class is that
     * class, and it has a type of it, the raw class or, for a class with type variables, a parameterized type (CDI 4.1
     * section 5.2.4). A producer the class declares is a bean of that class too, and is left out, as it has no such
     * type. Those with {@code @Default} when there are, else whatever their qualifiers (DRC-017). When the class has
     * no bean, the beans of that type with {@code @Default}: a superclass the extension scanned on its own is read on
     * the bean of its subclass. A class whose bean types {@code @Typed} restricts has none of either.
     */
    private Set<Bean<?>> beansOf(Class<?> beanClass) {
        Set<Bean<?>> withDefault = new HashSet<>();
        Set<Bean<?>> otherwiseQualified = new HashSet<>();
        for (Bean<?> bean : container.getBeans(Object.class, Any.Literal.INSTANCE)) {
            if (bean.getBeanClass() == beanClass && typeOfClass(bean, beanClass) != null) {
                (hasDefault(bean) ? withDefault : otherwiseQualified).add(bean);
            }
        }
        if (!withDefault.isEmpty()) {
            return withDefault;
        }
        if (!otherwiseQualified.isEmpty()) {
            return otherwiseQualified;
        }
        return container.getBeans(beanClass);
    }

    private static boolean hasDefault(Bean<?> bean) {
        for (var qualifier : bean.getQualifiers()) {
            if (qualifier.annotationType() == Default.class) {
                return true;
            }
        }
        return false;
    }

    /**
     * The bean type of {@code bean} that is {@code beanClass} or whose raw type is {@code beanClass}, or {@code null}
     * when it has none.
     */
    private static Type typeOfClass(Bean<?> bean, Class<?> beanClass) {
        for (Type type : bean.getTypes()) {
            if (type == beanClass
                    || (type instanceof ParameterizedType parameterized && parameterized.getRawType() == beanClass)) {
                return type;
            }
        }
        return null;
    }

    /** How the target of a gauge class is obtained, and the context to release when this bean created that target. */
    private record Lookup(Supplier<Object> target, CreationalContext<?> owned, AtomicBoolean releasedOnce) {

        Lookup(Supplier<Object> target, CreationalContext<?> owned) {
            this(target, owned, new AtomicBoolean());
        }

        void release() {
            if (owned != null && releasedOnce.compareAndSet(false, true)) {
                owned.release();
            }
        }
    }
}
