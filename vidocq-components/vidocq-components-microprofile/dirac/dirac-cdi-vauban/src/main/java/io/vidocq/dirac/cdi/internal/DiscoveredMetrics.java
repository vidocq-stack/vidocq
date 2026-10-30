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
import org.eclipse.microprofile.metrics.annotation.Gauge;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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

    private static final String BEAN_CLASS_REQUIRED = "beanClass must not be null";
    private static final String REGISTRIES_REQUIRED = "registries must not be null";

    private final Set<DiracExtension.ResolvedGauge> gauges = ConcurrentHashMap.newKeySet();
    private final Set<DiracExtension.PreRegisteredMetric> timers = ConcurrentHashMap.newKeySet();
    private final Set<DiracExtension.PreRegisteredMetric> counters = ConcurrentHashMap.newKeySet();

    /**
     * The union of {@code runs}, each metric once. DiracExtension may run at build time, from the codegen bundle,
     * and again when the container starts: each run registers its own synthetic bean, listing the classes it saw,
     * so both beans stay (BUG-20261030-27 of vidocq). A gauge is kept once per scope and {@link MetricID}: two runs
     * resolve the same gauge into records whose method handles differ.
     */
    static DiscoveredMetrics merge(Iterable<DiscoveredMetrics> runs) {
        var merged = new DiscoveredMetrics();
        var gaugeIds = new java.util.HashSet<String>();
        for (var run : runs) {
            for (var gauge : run.gauges) {
                if (gaugeIds.add(gauge.scope() + '\u0000' + gauge.metricID())) {
                    merged.gauges.add(gauge);
                }
            }
            merged.timers.addAll(run.timers);
            merged.counters.addAll(run.counters);
        }
        return merged;
    }

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
        Objects.requireNonNull(beanClass, BEAN_CLASS_REQUIRED);
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
        scan(beanClass, timers,
                element -> DiracExtension.MetricAnnotation.of(DiracExtension.findTimedOnElement(element)));
    }

    void scanCountedMethods(Class<?> beanClass) {
        scan(beanClass, counters,
                element -> DiracExtension.MetricAnnotation.of(DiracExtension.findCountedOnElement(element)));
    }

    /**
     * Collects the metrics of the methods and constructors of {@code beanClass}, the annotation
     * found by {@code finder} on a member taking precedence over the one on the class.
     */
    private static void scan(Class<?> beanClass, Set<DiracExtension.PreRegisteredMetric> target,
                             Function<AnnotatedElement, DiracExtension.MetricAnnotation> finder) {
        Objects.requireNonNull(beanClass, BEAN_CLASS_REQUIRED);
        if (beanClass.isAnnotationPresent(jakarta.interceptor.Interceptor.class)) return;
        var onClass = finder.apply(beanClass);
        for (var method : beanClass.getDeclaredMethods()) {
            var metric = methodMetric(beanClass, method, onClass, finder);
            if (metric != null) {
                target.add(metric);
            }
        }
        for (var constructor : beanClass.getDeclaredConstructors()) {
            var metric = constructorMetric(beanClass, constructor, onClass, finder);
            if (metric != null) {
                target.add(metric);
            }
        }
    }

    private static DiracExtension.PreRegisteredMetric methodMetric(
            Class<?> beanClass, Method method, DiracExtension.MetricAnnotation onClass,
            Function<AnnotatedElement, DiracExtension.MetricAnnotation> finder) {
        if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic() || method.isBridge()
                || method.getName().contains("$$")) {
            return null;
        }
        var onMethod = finder.apply(method);
        boolean classLevel = onMethod == null;
        if (classLevel && (onClass == null || Modifier.isPrivate(method.getModifiers()))) {
            return null;
        }
        return DiracExtension.resolvePreRegisteredMetric(beanClass, method, classLevel ? onClass : onMethod,
                classLevel);
    }

    private static DiracExtension.PreRegisteredMetric constructorMetric(
            Class<?> beanClass, Constructor<?> constructor, DiracExtension.MetricAnnotation onClass,
            Function<AnnotatedElement, DiracExtension.MetricAnnotation> finder) {
        if (Modifier.isPrivate(constructor.getModifiers())) {
            return null;
        }
        var onConstructor = finder.apply(constructor);
        boolean classLevel = onConstructor == null;
        var metric = classLevel ? onClass : onConstructor;
        if (metric == null) {
            return null;
        }
        var metricName = classLevel
                ? DiracExtension.resolveClassLevelConstructorMetricName(beanClass, metric.name(), metric.absolute())
                : DiracExtension.resolveConstructorLevelMetricName(beanClass, metric.name(), metric.absolute());
        var metadata = Metadata.builder()
                .withName(metricName).withDescription(metric.description()).withUnit(metric.unit()).build();
        return new DiracExtension.PreRegisteredMetric(metadata,
                DiracExtension.parseTags(metric.tags().toArray(String[]::new)),
                DiracExtension.normalizeScope(metric.scope()));
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
        Objects.requireNonNull(registries, REGISTRIES_REQUIRED);
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
        Objects.requireNonNull(registries, REGISTRIES_REQUIRED);
        for (var resolved : timers) {
            registries.registry(resolved.scope()).timer(resolved.metadata(), resolved.tags());
        }
    }

    void registerCounters(MetricRegistryProducerBean registries) {
        Objects.requireNonNull(registries, REGISTRIES_REQUIRED);
        for (var resolved : counters) {
            registries.registry(resolved.scope()).counter(resolved.metadata(), resolved.tags());
        }
    }

    private static org.eclipse.microprofile.metrics.Gauge<Number> toGaugeMetric(
            DiracExtension.ResolvedGauge resolved, Function<Class<?>, Object> beanResolver) {
        if (resolved.invoker() != null) {
            // CG-05 companion path: direct functional accessor, no MethodHandle.
            return resolved.staticMethod()
                    ? new io.vidocq.dirac.internal.FunctionalGaugeImpl<>(resolved.invoker())
                    : new io.vidocq.dirac.internal.FunctionalGaugeImpl<>(
                            () -> beanResolver.apply(resolved.beanClass()), resolved.invoker());
        }
        return resolved.staticMethod()
                ? new GaugeImpl<>(resolved.methodHandle())
                : new GaugeImpl<>(() -> beanResolver.apply(resolved.beanClass()), resolved.methodHandle());
    }
}
