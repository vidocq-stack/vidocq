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
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.inject.Inject;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Map;
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

    // The beans of this container, for a gauge class with type variables that no lookup by type finds (DRC-015).
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

    private Lookup lookup(Class<?> beanClass) {
        // The lookup by type at each read, as before, for every class but one with type variables that it does not
        // find.
        Lookup byType = new Lookup(() -> beans.select(beanClass).get(), null);
        if (beanClass.getTypeParameters().length == 0 || !beans.select(beanClass).isUnsatisfied()) {
            return byType;
        }
        // A class with a bounded type variable is not a type of its own bean, whose type of that class is
        // parameterized: the raw class does not look it up (CDI 4.1 section 5.2.4). Its bean is the one whose bean
        // class is that class and that has a parameterized type of it, among the beans the lookup above asks for
        // (those with @Default). This leaves out a producer the class declares (its bean class is the class too)
        // and a class whose bean types @Typed restricts: neither is ever created by a read of a gauge.
        Set<Bean<?>> ofThatClass = new HashSet<>();
        for (Bean<?> bean : container.getBeans(Object.class)) {
            if (bean.getBeanClass() == beanClass && typeOfClass(bean, beanClass) != null) {
                ofThatClass.add(bean);
            }
        }
        if (ofThatClass.isEmpty()) {
            return byType;
        }
        CreationalContext<?> context = null;
        try {
            Bean<?> bean = container.resolve(ofThatClass);
            context = container.createCreationalContext(bean);
            Object target = container.getReference(bean, typeOfClass(bean, beanClass), context);
            return new Lookup(() -> target, context);
        } catch (RuntimeException failure) {
            // Decided too: each read fails with this cause, and none resolves or creates the bean again.
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

    /** The bean type of {@code bean} whose raw type is {@code beanClass}, or {@code null} when it has none. */
    private static Type typeOfClass(Bean<?> bean, Class<?> beanClass) {
        for (Type type : bean.getTypes()) {
            if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == beanClass) {
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
