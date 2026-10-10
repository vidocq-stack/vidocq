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

import io.vidocq.dirac.internal.GaugeImpl;
import io.vidocq.dirac.internal.MetricRegistryImpl;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.eclipse.microprofile.metrics.annotation.Timed;

import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The metrics one container discovered: its {@code @Gauge} methods and the {@code @Timed} and
 * {@code @Counted} metrics to pre-register. One instance per container, made by
 * {@link DiscoveredMetricsCreator} from the classes {@link DiracExtension} found, so two containers
 * of one class loader never see each other's metrics (dirac#23).
 */
public final class DiscoveredMetrics {

    private final Set<DiracExtension.ResolvedGauge> gauges = ConcurrentHashMap.newKeySet();
    private final Set<DiracExtension.PreRegisteredMetric> timers = ConcurrentHashMap.newKeySet();
    private final Set<DiracExtension.PreRegisteredMetric> counters = ConcurrentHashMap.newKeySet();

    /** Resolves the metrics of {@code beanClass}: its compile-time companion, else its annotations. */
    void ingest(Class<?> beanClass) {
        // CG-05: compile-time $$DiracMetrics companion first; the reflective annotation scan stays
        // as the documented fallback for classes compiled without the dirac-processor.
        var companion = CompanionRegistry.resolve(beanClass);
        if (companion != null) {
            ingestCompanion(companion);
            return;
        }
        CompanionRegistry.noteScanFallback();
        scanGaugeMethods(beanClass);
        scanTimedMethods(beanClass);
        scanCountedMethods(beanClass);
    }

    boolean isEmpty() {
        return gauges.isEmpty() && timers.isEmpty() && counters.isEmpty();
    }

    Set<DiracExtension.ResolvedGauge> gauges() {
        return Collections.unmodifiableSet(gauges);
    }

    Set<DiracExtension.PreRegisteredMetric> timers() {
        return Collections.unmodifiableSet(timers);
    }

    Set<DiracExtension.PreRegisteredMetric> counters() {
        return Collections.unmodifiableSet(counters);
    }

    /** Ingests fully-resolved compile-time metadata — no annotation is read. */
    void ingestCompanion(io.vidocq.dirac.spi.gen.MetricsCompanion companion) {
        for (var spec : companion.timers()) {
            timers.add(toPreRegistered(spec));
        }
        for (var spec : companion.counters()) {
            counters.add(toPreRegistered(spec));
        }
        for (var gauge : companion.gauges()) {
            var tags = DiracExtension.parseTags(gauge.tags().toArray(new String[0]));
            gauges.add(new DiracExtension.ResolvedGauge(
                    companion.beanClass(),
                    null,
                    gauge.staticMethod(),
                    new MetricID(gauge.name(), tags),
                    Metadata.builder()
                            .withName(gauge.name())
                            .withDescription(gauge.description())
                            .withUnit(gauge.unit())
                            .build(),
                    tags,
                    DiracExtension.normalizeScope(gauge.scope()),
                    gauge.invoker()));
        }
    }

    private static DiracExtension.PreRegisteredMetric toPreRegistered(io.vidocq.dirac.spi.gen.MetricsCompanion.MetricSpec spec) {
        return new DiracExtension.PreRegisteredMetric(
                Metadata.builder()
                        .withName(spec.name())
                        .withDescription(spec.description())
                        .withUnit(spec.unit())
                        .build(),
                DiracExtension.parseTags(spec.tags().toArray(new String[0])),
                DiracExtension.normalizeScope(spec.scope()));
    }

    void scanGaugeMethods(Class<?> beanClass) {
        Objects.requireNonNull(beanClass, "beanClass must not be null");
        if (beanClass.isAnnotationPresent(jakarta.interceptor.Interceptor.class)) return;
        for (var method : beanClass.getDeclaredMethods()) {
            var gauge = method.getAnnotation(Gauge.class);
            if (gauge == null) {
                continue;
            }
            gauges.add(DiracExtension.resolveGauge(beanClass, method, gauge));
        }
    }

    void scanTimedMethods(Class<?> beanClass) {
        Objects.requireNonNull(beanClass, "beanClass must not be null");
        if (beanClass.isAnnotationPresent(jakarta.interceptor.Interceptor.class)) return;
        var classTimed = DiracExtension.findTimedOnElement(beanClass);
        for (var method : beanClass.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic() || method.isBridge()) continue;
            if (method.getName().contains("$$")) continue;
            var methodTimed = DiracExtension.findTimedOnElement(method);
            final Timed timed = methodTimed != null ? methodTimed : classTimed;
            if (timed == null) continue;
            boolean classLevel = (methodTimed == null);
            if (classLevel && Modifier.isPrivate(method.getModifiers())) continue;
            timers.add(DiracExtension.resolvePreRegisteredMetric(beanClass, method, timed.name(), timed.absolute(),
                    timed.description(), timed.unit(), timed.tags(), DiracExtension.normalizeScope(timed.scope()), classLevel));
        }
        for (var constructor : beanClass.getDeclaredConstructors()) {
            if (Modifier.isPrivate(constructor.getModifiers())) continue;
            var constructorTimed = DiracExtension.findTimedOnElement(constructor);
            final Timed timed = constructorTimed != null ? constructorTimed : classTimed;
            if (timed == null) continue;
            boolean classLevel = (constructorTimed == null);
            var metricName = classLevel
                    ? DiracExtension.resolveClassLevelConstructorMetricName(beanClass, timed)
                    : DiracExtension.resolveConstructorLevelMetricName(beanClass, timed.name(), timed.absolute());
            var tags = DiracExtension.parseTags(timed.tags());
            var metadata = Metadata.builder()
                    .withName(metricName).withDescription(timed.description()).withUnit(timed.unit()).build();
            timers.add(new DiracExtension.PreRegisteredMetric(metadata, tags, DiracExtension.normalizeScope(timed.scope())));
        }
    }

    void scanCountedMethods(Class<?> beanClass) {
        Objects.requireNonNull(beanClass, "beanClass must not be null");
        if (beanClass.isAnnotationPresent(jakarta.interceptor.Interceptor.class)) return;
        var classCounted = DiracExtension.findCountedOnElement(beanClass);
        for (var method : beanClass.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic() || method.isBridge()) continue;
            if (method.getName().contains("$$")) continue;
            var methodCounted = DiracExtension.findCountedOnElement(method);
            final Counted counted = methodCounted != null ? methodCounted : classCounted;
            if (counted == null) continue;
            boolean classLevel = (methodCounted == null);
            if (classLevel && Modifier.isPrivate(method.getModifiers())) continue;
            counters.add(DiracExtension.resolvePreRegisteredMetric(beanClass, method, counted.name(), counted.absolute(),
                    counted.description(), counted.unit(), counted.tags(), DiracExtension.normalizeScope(counted.scope()), classLevel));
        }
        for (var constructor : beanClass.getDeclaredConstructors()) {
            if (Modifier.isPrivate(constructor.getModifiers())) continue;
            var constructorCounted = DiracExtension.findCountedOnElement(constructor);
            final Counted counted = constructorCounted != null ? constructorCounted : classCounted;
            if (counted == null) continue;
            boolean classLevel = (constructorCounted == null);
            var metricName = classLevel
                    ? DiracExtension.resolveClassLevelConstructorMetricName(beanClass, counted.name(), counted.absolute())
                    : DiracExtension.resolveConstructorLevelMetricName(beanClass, counted.name(), counted.absolute());
            var tags = DiracExtension.parseTags(counted.tags());
            var metadata = Metadata.builder()
                    .withName(metricName).withDescription(counted.description())
                    .withUnit(counted.unit()).build();
            counters.add(new DiracExtension.PreRegisteredMetric(metadata, tags, DiracExtension.normalizeScope(counted.scope())));
        }
    }

    void registerGauges(MetricRegistry registry, Function<Class<?>, Object> beanResolver) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(beanResolver, "beanResolver must not be null");
        for (var resolved : gauges) {
            var metric = toGaugeMetric(resolved, beanResolver);

            if (registry instanceof MetricRegistryImpl implementation) {
                implementation.register(resolved.metadata(), metric, resolved.tags());
            } else {
                registry.gauge(resolved.metadata(), metric::getValue, resolved.tags());
            }
        }
    }

    void registerGauges(MetricRegistryProducerBean registries, Function<Class<?>, Object> beanResolver) {
        Objects.requireNonNull(registries, "registries must not be null");
        Objects.requireNonNull(beanResolver, "beanResolver must not be null");
        for (var resolved : gauges) {
            var targetRegistry = registries.registry(resolved.scope());
            var metric = toGaugeMetric(resolved, beanResolver);
            if (targetRegistry instanceof MetricRegistryImpl implementation) {
                implementation.register(resolved.metadata(), metric, resolved.tags());
            } else {
                targetRegistry.gauge(resolved.metadata(), metric::getValue, resolved.tags());
            }
        }
    }

    void registerTimers(MetricRegistryProducerBean registries) {
        Objects.requireNonNull(registries, "registries must not be null");
        for (var resolved : timers) {
            registries.registry(resolved.scope()).timer(resolved.metadata(), resolved.tags());
        }
    }

    void registerCounters(MetricRegistryProducerBean registries) {
        Objects.requireNonNull(registries, "registries must not be null");
        for (var resolved : counters) {
            registries.registry(resolved.scope()).counter(resolved.metadata(), resolved.tags());
        }
    }

    private static org.eclipse.microprofile.metrics.Gauge<Number> toGaugeMetric(
            DiracExtension.ResolvedGauge resolved, Function<Class<?>, Object> beanResolver) {
        if (resolved.invoker() != null) {
            // CG-05 companion path: direct functional accessor, no MethodHandle.
            return resolved.staticMethod()
                    ? new io.vidocq.dirac.internal.FunctionalGaugeImpl<Number>(resolved.invoker())
                    : new io.vidocq.dirac.internal.FunctionalGaugeImpl<Number>(
                            () -> beanResolver.apply(resolved.beanClass()), resolved.invoker());
        }
        return resolved.staticMethod()
                ? new GaugeImpl<Number>(resolved.methodHandle())
                : new GaugeImpl<Number>(() -> beanResolver.apply(resolved.beanClass()), resolved.methodHandle());
    }
}
