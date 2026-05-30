package io.vidocq.dirac.cdi.internal;

import io.vidocq.dirac.api.DiracException;
import io.vidocq.dirac.internal.GaugeImpl;
import io.vidocq.dirac.internal.MetricRegistryImpl;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Discovery;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.MetaAnnotations;
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
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Dirac CDI 4.1 BCE — discovers, validates, and resolves {@code @Gauge}, {@code @Timed}
 * and {@code @Counted} at startup.
 */
public class DiracExtension implements BuildCompatibleExtension {
    private static final Set<ResolvedGauge> DISCOVERED_GAUGES = ConcurrentHashMap.newKeySet();
    private static final Set<PreRegisteredMetric> DISCOVERED_TIMERS = ConcurrentHashMap.newKeySet();
    private static final Set<PreRegisteredMetric> DISCOVERED_COUNTERS = ConcurrentHashMap.newKeySet();

    public DiracExtension() {
    }

    @Discovery
    public void registerCustomInterceptorBindings(MetaAnnotations meta) {
        DISCOVERED_GAUGES.clear();
        DISCOVERED_TIMERS.clear();
        DISCOVERED_COUNTERS.clear();
        meta.addInterceptorBinding(Timed.class);
        meta.addInterceptorBinding(Counted.class);
    }

    @Enhancement(types = Object.class, withSubtypes = true)
    public void collectMetricAnnotations(ClassInfo classInfo, Messages messages) {
        var beanClassName = classInfo.name();
        try {
            var beanClass = Class.forName(beanClassName, false, Thread.currentThread().getContextClassLoader());
            scanGaugeMethods(beanClass);
            scanTimedMethods(beanClass);
            scanCountedMethods(beanClass);
        } catch (ClassNotFoundException exception) {
            if (messages != null) messages.error("Unable to load metric candidate class '" + beanClassName + "': " + exception.getMessage());
        } catch (DiracException exception) {
            if (messages != null) messages.error(exception.getMessage());
            throw exception;
        }
    }

    static void clearDiscoveredGauges() {
        DISCOVERED_GAUGES.clear();
    }

    static void clearDiscoveredTimers() {
        DISCOVERED_TIMERS.clear();
    }

    static void clearDiscoveredCounters() {
        DISCOVERED_COUNTERS.clear();
    }

    static int discoveredGaugeCount() {
        return DISCOVERED_GAUGES.size();
    }

    static Set<ResolvedGauge> discoveredGauges() {
        return Collections.unmodifiableSet(DISCOVERED_GAUGES);
    }

    static Set<PreRegisteredMetric> discoveredTimers() {
        return Collections.unmodifiableSet(DISCOVERED_TIMERS);
    }

    static Set<PreRegisteredMetric> discoveredCounters() {
        return Collections.unmodifiableSet(DISCOVERED_COUNTERS);
    }

    static void scanGaugeMethods(Class<?> beanClass) {
        Objects.requireNonNull(beanClass, "beanClass must not be null");
        if (beanClass.isAnnotationPresent(jakarta.interceptor.Interceptor.class)) return;
        for (var method : beanClass.getDeclaredMethods()) {
            var gauge = method.getAnnotation(Gauge.class);
            if (gauge == null) {
                continue;
            }
            DISCOVERED_GAUGES.add(resolveGauge(beanClass, method, gauge));
        }
    }

    static void scanTimedMethods(Class<?> beanClass) {
        Objects.requireNonNull(beanClass, "beanClass must not be null");
        if (beanClass.isAnnotationPresent(jakarta.interceptor.Interceptor.class)) return;
        var classTimed = findTimedOnElement(beanClass);
        for (var method : beanClass.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic() || method.isBridge()) continue;
            if (method.getName().contains("$$")) continue;
            var methodTimed = findTimedOnElement(method);
            final Timed timed = methodTimed != null ? methodTimed : classTimed;
            if (timed == null) continue;
            boolean classLevel = (methodTimed == null);
            if (classLevel && Modifier.isPrivate(method.getModifiers())) continue;
            DISCOVERED_TIMERS.add(resolvePreRegisteredMetric(beanClass, method, timed.name(), timed.absolute(),
                    timed.description(), timed.unit(), timed.tags(), normalizeScope(timed.scope()), classLevel));
        }
        for (var constructor : beanClass.getDeclaredConstructors()) {
            if (Modifier.isPrivate(constructor.getModifiers())) continue;
            var constructorTimed = findTimedOnElement(constructor);
            final Timed timed = constructorTimed != null ? constructorTimed : classTimed;
            if (timed == null) continue;
            boolean classLevel = (constructorTimed == null);
            var metricName = classLevel
                    ? resolveClassLevelConstructorMetricName(beanClass, timed)
                    : resolveConstructorLevelMetricName(beanClass, timed.name(), timed.absolute());
            var tags = parseTags(timed.tags());
            var metadata = Metadata.builder()
                    .withName(metricName).withDescription(timed.description()).withUnit(timed.unit()).build();
            DISCOVERED_TIMERS.add(new PreRegisteredMetric(metadata, tags, normalizeScope(timed.scope())));
        }
    }

    static void scanCountedMethods(Class<?> beanClass) {
        Objects.requireNonNull(beanClass, "beanClass must not be null");
        if (beanClass.isAnnotationPresent(jakarta.interceptor.Interceptor.class)) return;
        var classCounted = findCountedOnElement(beanClass);
        for (var method : beanClass.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic() || method.isBridge()) continue;
            if (method.getName().contains("$$")) continue;
            var methodCounted = findCountedOnElement(method);
            final Counted counted = methodCounted != null ? methodCounted : classCounted;
            if (counted == null) continue;
            boolean classLevel = (methodCounted == null);
            if (classLevel && Modifier.isPrivate(method.getModifiers())) continue;
            DISCOVERED_COUNTERS.add(resolvePreRegisteredMetric(beanClass, method, counted.name(), counted.absolute(),
                    counted.description(), counted.unit(), counted.tags(), normalizeScope(counted.scope()), classLevel));
        }
        for (var constructor : beanClass.getDeclaredConstructors()) {
            if (Modifier.isPrivate(constructor.getModifiers())) continue;
            var constructorCounted = findCountedOnElement(constructor);
            final Counted counted = constructorCounted != null ? constructorCounted : classCounted;
            if (counted == null) continue;
            boolean classLevel = (constructorCounted == null);
            var metricName = classLevel
                    ? resolveClassLevelConstructorMetricName(beanClass, counted.name(), counted.absolute())
                    : resolveConstructorLevelMetricName(beanClass, counted.name(), counted.absolute());
            var tags = parseTags(counted.tags());
            var metadata = Metadata.builder()
                    .withName(metricName).withDescription(counted.description())
                    .withUnit(counted.unit()).build();
            DISCOVERED_COUNTERS.add(new PreRegisteredMetric(metadata, tags, normalizeScope(counted.scope())));
        }
    }

    static void registerDiscoveredGauges(MetricRegistry registry, Function<Class<?>, Object> beanResolver) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(beanResolver, "beanResolver must not be null");
        for (var resolved : DISCOVERED_GAUGES) {
            var metric = toGaugeMetric(resolved, beanResolver);

            if (registry instanceof MetricRegistryImpl implementation) {
                implementation.register(resolved.metadata(), metric, resolved.tags());
            } else {
                registry.gauge(resolved.metadata(), metric::getValue, resolved.tags());
            }
        }
    }

    static void registerDiscoveredGauges(MetricRegistryProducerBean registries, Function<Class<?>, Object> beanResolver) {
        Objects.requireNonNull(registries, "registries must not be null");
        Objects.requireNonNull(beanResolver, "beanResolver must not be null");
        for (var resolved : DISCOVERED_GAUGES) {
            var targetRegistry = registries.registry(resolved.scope());
            var metric = toGaugeMetric(resolved, beanResolver);
            if (targetRegistry instanceof MetricRegistryImpl implementation) {
                implementation.register(resolved.metadata(), metric, resolved.tags());
            } else {
                targetRegistry.gauge(resolved.metadata(), metric::getValue, resolved.tags());
            }
        }
    }

    static void registerDiscoveredTimers(MetricRegistryProducerBean registries) {
        Objects.requireNonNull(registries, "registries must not be null");
        for (var resolved : DISCOVERED_TIMERS) {
            registries.registry(resolved.scope()).timer(resolved.metadata(), resolved.tags());
        }
    }

    static void registerDiscoveredCounters(MetricRegistryProducerBean registries) {
        Objects.requireNonNull(registries, "registries must not be null");
        for (var resolved : DISCOVERED_COUNTERS) {
            registries.registry(resolved.scope()).counter(resolved.metadata(), resolved.tags());
        }
    }

    private static GaugeImpl<Number> toGaugeMetric(ResolvedGauge resolved, Function<Class<?>, Object> beanResolver) {
        return resolved.staticMethod()
                ? new GaugeImpl<Number>(resolved.methodHandle())
                : new GaugeImpl<Number>(() -> beanResolver.apply(resolved.beanClass()), resolved.methodHandle());
    }

    private static ResolvedGauge resolveGauge(Class<?> beanClass, Method method, Gauge gauge) {
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
                normalizeScope(gauge.scope())
        );
    }

    private static PreRegisteredMetric resolvePreRegisteredMetric(Class<?> beanClass, Method method, String name, boolean absolute,
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

    private static Timed findTimedOnElement(java.lang.reflect.AnnotatedElement element) {
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

    private static Counted findCountedOnElement(java.lang.reflect.AnnotatedElement element) {
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
            var lookup = MethodHandles.privateLookupIn(beanClass, MethodHandles.lookup());
            var methodType = MethodType.methodType(method.getReturnType());
            return Modifier.isStatic(method.getModifiers())
                    ? lookup.findStatic(beanClass, method.getName(), methodType)
                    : lookup.findVirtual(beanClass, method.getName(), methodType);
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new DiracException("Unable to resolve @Gauge method handle for '" + method + "'", exception);
        }
    }

    private static String normalizeScope(String scope) {
        var normalized = scope == null ? MetricRegistry.APPLICATION_SCOPE : scope.trim();
        return normalized.isEmpty() ? MetricRegistry.APPLICATION_SCOPE : normalized;
    }

    private static Tag[] parseTags(String[] tagValues) {
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
                         String scope) {
    }

    record PreRegisteredMetric(Metadata metadata, Tag[] tags, String scope) {
    }
}
