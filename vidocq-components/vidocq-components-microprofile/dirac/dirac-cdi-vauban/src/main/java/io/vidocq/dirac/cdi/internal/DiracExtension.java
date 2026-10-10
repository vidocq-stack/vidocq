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
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.eclipse.microprofile.metrics.annotation.Timed;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Dirac CDI 4.1 BCE — discovers, validates, and resolves {@code @Gauge}, {@code @Timed}
 * and {@code @Counted} at startup.
 */
public class DiracExtension implements BuildCompatibleExtension {

    private static final System.Logger LOG = System.getLogger(DiracExtension.class.getName());
    // Per container (one extension instance per container start): the classes that carry metrics.
    // The metrics themselves are resolved again, for this container only, by DiscoveredMetricsCreator.
    // A static set here was shared by every container of the class loader (dirac#23).
    private final Set<String> metricClasses = ConcurrentHashMap.newKeySet();

    public DiracExtension() {
    }

    @Discovery
    public void registerCustomInterceptorBindings(MetaAnnotations meta) {
        meta.addInterceptorBinding(Timed.class);
        meta.addInterceptorBinding(Counted.class);
    }

    @Enhancement(types = Object.class, withSubtypes = true)
    public void collectMetricAnnotations(ClassInfo classInfo, Messages messages) {
        var beanClassName = classInfo.name();
        try {
            var beanClass = Class.forName(beanClassName, false, Thread.currentThread().getContextClassLoader());
            // CG-05: compile-time $$DiracMetrics companion first; the reflective
            // annotation scan below stays as the documented fallback for classes
            // compiled without the dirac-processor.
            // Resolving the metrics here validates them (a bad @Gauge fails the deployment); only the
            // class name is kept, for the synthetic DiscoveredMetrics bean of this container.
            var probe = new DiscoveredMetrics();
            probe.ingest(beanClass);
            if (!probe.isEmpty()) {
                metricClasses.add(beanClassName);
            }
        } catch (ClassNotFoundException | LinkageError notVisible) {
            // Not a class of this application: the enhancement also visits the container's own
            // types (Open Liberty's transaction beans, for one), which the application's class
            // loader cannot see. They carry no application metric; reporting them as errors failed
            // the deployment (dirac#23).
            LOG.log(System.Logger.Level.DEBUG, "Metric candidate {0} is not visible to the application: {1}",
                    beanClassName, notVisible.toString());
        } catch (DiracException exception) {
            if (messages != null) messages.error(exception.getMessage());
            throw exception;
        }
    }

    /**
     * Registers this container's {@link DiscoveredMetrics}: a synthetic bean whose parameter lists the
     * classes {@link #collectMetricAnnotations} found, resolved again by {@link DiscoveredMetricsCreator}
     * in the container. {@link GaugeRegistrationBean} registers them in that container's registries.
     */
    @Synthesis
    public void registerDiscoveredMetrics(SyntheticComponents components) {
        components.addBean(DiscoveredMetrics.class)
                .type(DiscoveredMetrics.class)
                .scope(Dependent.class)
                .withParam(DiscoveredMetricsCreator.CLASSES, metricClasses.toArray(String[]::new))
                .createWith(DiscoveredMetricsCreator.class);
    }

    static ResolvedGauge resolveGauge(Class<?> beanClass, Method method, Gauge gauge) {
        validateGaugeSignature(method);
        var metricName = resolveMetricName(method, gauge);
        var tags = parseTags(gauge.tags());
        return new ResolvedGauge(
                beanClass,
                resolveMethodHandle(beanClass, method),
                Modifier.isStatic(method.getModifiers()),
                new MetricID(metricName, tags),
                Metadata.builder()
                        .withName(metricName)
                        .withDescription(gauge.description())
                        .withUnit(gauge.unit())
                        .build(),
                tags,
                normalizeScope(gauge.scope()),
                null
        );
    }

    static PreRegisteredMetric resolvePreRegisteredMetric(Class<?> beanClass, Method method, String name, boolean absolute,
                                                                    String description, String unit,
                                                                    String[] tags, String scope, boolean classLevel) {
        var metricName = resolveSimpleMetricName(beanClass, method, name, absolute, classLevel);
        var parsedTags = parseTags(tags);
        var metadata = Metadata.builder()
                .withName(metricName)
                .withDescription(description)
                .withUnit(unit)
                .build();
        return new PreRegisteredMetric(metadata, parsedTags, scope);
    }

    static String resolveClassLevelConstructorMetricName(Class<?> beanClass, Timed timed) {
        return resolveClassLevelConstructorMetricName(beanClass, timed.name(), timed.absolute());
    }

    static String resolveClassLevelConstructorMetricName(Class<?> beanClass, String name, boolean absolute) {
        var explicitName = name == null ? "" : name.trim();
        var simpleName = beanClass.getSimpleName();
        if (!explicitName.isEmpty()) {
            if (absolute) return explicitName + "." + simpleName;
            var pkg = beanClass.getPackageName();
            return pkg.isEmpty() ? explicitName + "." + simpleName : pkg + "." + explicitName + "." + simpleName;
        }
        return MetricRegistry.name(beanClass, simpleName);
    }

    static String resolveConstructorLevelMetricName(Class<?> beanClass, String name, boolean absolute) {
        var explicitName = name == null ? "" : name.trim();
        var simpleName = beanClass.getSimpleName();
        if (!explicitName.isEmpty()) {
            return absolute ? explicitName : MetricRegistry.name(beanClass, explicitName);
        }
        return absolute ? simpleName : MetricRegistry.name(beanClass, simpleName);
    }

    private static String resolveSimpleMetricName(Class<?> beanClass, Method method, String name, boolean absolute, boolean classLevel) {
        var explicitName = name == null ? "" : name.trim();
        if (!explicitName.isEmpty()) {
            if (absolute) return explicitName;
            if (classLevel) {
                var pkg = beanClass.getPackageName();
                return pkg.isEmpty()
                        ? explicitName + "." + method.getName()
                        : pkg + "." + explicitName + "." + method.getName();
            }
            return MetricRegistry.name(beanClass, explicitName);
        }
        return absolute ? method.getName() : MetricRegistry.name(beanClass, method.getName());
    }

    static Timed findTimedOnElement(java.lang.reflect.AnnotatedElement element) {
        var direct = element.getAnnotation(Timed.class);
        if (direct != null) {
            return direct;
        }
        for (var annotation : element.getAnnotations()) {
            var meta = annotation.annotationType().getAnnotation(Timed.class);
            if (meta != null) {
                return meta;
            }
        }
        return null;
    }

    static Counted findCountedOnElement(java.lang.reflect.AnnotatedElement element) {
        var direct = element.getAnnotation(Counted.class);
        if (direct != null) {
            return direct;
        }
        for (var annotation : element.getAnnotations()) {
            var meta = annotation.annotationType().getAnnotation(Counted.class);
            if (meta != null) {
                return meta;
            }
        }
        return null;
    }

    private static String resolveMetricName(Method method, Gauge gauge) {
        var explicitName = gauge.name().trim();
        if (!explicitName.isEmpty()) {
            return gauge.absolute() ? explicitName : MetricRegistry.name(method.getDeclaringClass(), explicitName);
        }
        return gauge.absolute() ? method.getName() : MetricRegistry.name(method.getDeclaringClass(), method.getName());
    }

    private static void validateGaugeSignature(Method method) {
        if (method.getParameterCount() != 0) {
            throw new DiracException("@Gauge method '" + method + "' must not declare parameters");
        }
        if (Void.TYPE.equals(method.getReturnType())) {
            throw new DiracException("@Gauge method '" + method + "' must not return void");
        }
        var returnType = method.getReturnType();
        boolean isNumeric = Number.class.isAssignableFrom(returnType)
                || returnType == byte.class || returnType == short.class
                || returnType == int.class || returnType == long.class
                || returnType == float.class || returnType == double.class;
        if (!isNumeric) {
            throw new DiracException("@Gauge method '" + method + "' must return a java.lang.Number");
        }
    }

    private static MethodHandle resolveMethodHandle(Class<?> beanClass, Method method) {
        try {
            // The bean lives in the application module, which opens its package for reflection
            // (CDI/JAX-RS) but is not read by the Dirac module. privateLookupIn additionally
            // requires the caller (Dirac) module to read the bean's module, so add that
            // readability edge here. No-op on the class-path / unnamed modules.
            var diracModule = DiracExtension.class.getModule();
            var beanModule = beanClass.getModule();
            if (diracModule.isNamed() && beanModule != diracModule && !diracModule.canRead(beanModule)) {
                diracModule.addReads(beanModule);
            }
            var lookup = MethodHandles.privateLookupIn(beanClass, MethodHandles.lookup());
            var methodType = MethodType.methodType(method.getReturnType());
            return Modifier.isStatic(method.getModifiers())
                    ? lookup.findStatic(beanClass, method.getName(), methodType)
                    : lookup.findVirtual(beanClass, method.getName(), methodType);
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new DiracException("Unable to resolve @Gauge method handle for '" + method + "'", exception);
        }
    }

    static String normalizeScope(String scope) {
        var normalized = scope == null ? MetricRegistry.APPLICATION_SCOPE : scope.trim();
        return normalized.isEmpty() ? MetricRegistry.APPLICATION_SCOPE : normalized;
    }

    static Tag[] parseTags(String[] tagValues) {
        if (tagValues == null || tagValues.length == 0) {
            return new Tag[0];
        }

        return Arrays.stream(tagValues)
                .filter(tag -> tag != null && !tag.isBlank())
                .map(DiracExtension::parseTag)
                .toArray(Tag[]::new);
    }

    private static Tag parseTag(String source) {
        var separator = source.indexOf('=');
        if (separator <= 0 || separator == source.length() - 1) {
            throw new DiracException("Invalid @Gauge tag declaration: '" + source + "'");
        }
        try {
            var key = source.substring(0, separator).trim();
            var value = source.substring(separator + 1).trim();
            // Validate reserved tag names according to spec §4.1
            if ("mp_scope".equalsIgnoreCase(key) || "mp_app".equalsIgnoreCase(key)) {
                throw new IllegalArgumentException("Tag name '" + key + "' is reserved");
            }
            return new Tag(key, value);
        } catch (IllegalArgumentException exception) {
            throw new DiracException("Invalid @Gauge tag declaration: '" + source + "'", exception);
        }
    }


    record ResolvedGauge(Class<?> beanClass,
                         MethodHandle methodHandle,
                         boolean staticMethod,
                         MetricID metricID,
                         Metadata metadata,
                         Tag[] tags,
                         String scope,
                         Function<Object, Number> invoker) {
    }

    record PreRegisteredMetric(Metadata metadata, Tag[] tags, String scope) {
    }
}
