package io.vidocq.dirac.cdi.internal;

import io.vidocq.dirac.api.DiracException;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundConstruct;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;
import org.eclipse.microprofile.metrics.annotation.Counted;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepteur CDI pour {@link Counted}.
 */
@Interceptor
@Counted
@Priority(4020)
public class CountedInterceptor {
    private final ConcurrentHashMap<CacheKey, ResolvedCounter> counters = new ConcurrentHashMap<>();

    @Inject
    MetricRegistryProducerBean registries;

    public CountedInterceptor() {
    }

    CountedInterceptor(MetricRegistryProducerBean registries) {
        this.registries = Objects.requireNonNull(registries, "registries must not be null");
    }

    @AroundConstruct
    public void aroundConstruct(InvocationContext context) throws Exception {
        var constructor = context.getConstructor();
        Class<?> beanClass = constructor.getDeclaringClass();
        if (beanClass.getName().contains("$$")) beanClass = beanClass.getSuperclass();
        var classCounted = beanClass.getAnnotation(Counted.class);
        var constructorCounted = constructor.getAnnotation(Counted.class);
        final Counted effective = constructorCounted != null ? constructorCounted : classCounted;

        if (effective != null && !java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())) {
            boolean classLevel = (constructorCounted == null);
            var metricName = classLevel
                    ? DiracExtension.resolveClassLevelConstructorMetricName(beanClass, effective.name(), effective.absolute())
                    : DiracExtension.resolveConstructorLevelMetricName(beanClass, effective.name(), effective.absolute());
            var tags = parseTags(effective.tags());
            var metadata = Metadata.builder()
                    .withName(metricName).withDescription(effective.description())
                    .withUnit(effective.unit()).build();
            var scope = normalizeScope(effective.scope());
            context.proceed();
            registries.registry(scope).counter(metadata, tags).inc();
        } else {
            context.proceed();
        }

        Object target = context.getTarget();
        if (target == null) return;
        Class<?> actualClass = target.getClass();
        if (actualClass.getName().contains("$$")) actualClass = actualClass.getSuperclass();
        preRegisterCounters(actualClass);
    }

    void preRegisterCounters(Class<?> beanClass) {
        var classCounted = beanClass.getAnnotation(Counted.class);
        // Scan all methods including inherited ones (excluding Object methods)
        for (var method : getAllDeclaredMethods(beanClass)) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) continue;
            if (method.getName().contains("$$")) continue;
            var methodCounted = method.getAnnotation(Counted.class);
            final Counted counted = methodCounted != null ? methodCounted : classCounted;
            if (counted == null) continue;
            boolean classLevel = (methodCounted == null);
            if (classLevel && java.lang.reflect.Modifier.isPrivate(method.getModifiers())) continue;
            var resolved = counters.computeIfAbsent(new CacheKey(beanClass, method),
                    ignored -> createResolvedCounter(beanClass, method, counted));
            registries.registry(resolved.scope()).counter(resolved.metadata(), resolved.tags());
        }
    }

    /**
     * Returns all methods declared in the class and its superclasses (except Object).
     */
    private static Method[] getAllDeclaredMethods(Class<?> beanClass) {
        var methods = new java.util.HashSet<Method>();
        var currentClass = beanClass;
        while (currentClass != null && currentClass != Object.class) {
            methods.addAll(java.util.Arrays.asList(currentClass.getDeclaredMethods()));
            currentClass = currentClass.getSuperclass();
        }
        return methods.toArray(new Method[0]);
    }

    @AroundInvoke
    public Object aroundInvoke(InvocationContext context) throws Exception {
        var resolved = resolveCounter(context);
        if (resolved != null) {
            var registry = registries.registry(resolved.scope());
            var counter = registry.getCounter(resolved.metricID());
            if (counter == null) {
                throw new IllegalStateException("Counter metric was removed from registry: " + resolved.metricID());
            }
            counter.inc();
        }
        return context.proceed();
    }

    private ResolvedCounter resolveCounter(InvocationContext context) {
        Objects.requireNonNull(context, "context must not be null");
        var method = Objects.requireNonNull(context.getMethod(), "InvocationContext method must not be null");
        var beanClass = context.getTarget() != null ? context.getTarget().getClass() : method.getDeclaringClass();
        if (beanClass.getName().contains("$$")) {
            beanClass = beanClass.getSuperclass();
        }
        method = normalizeInterceptedMethod(beanClass, method);
        final Class<?> resolvedBeanClass = beanClass;
        var counted = findCounted(method, beanClass);
        if (counted == null) {
            return null;
        }
        var sourceMethod = counted.sourceMethod();
        return counters.computeIfAbsent(new CacheKey(resolvedBeanClass, sourceMethod),
                ignored -> createResolvedCounter(resolvedBeanClass, sourceMethod, counted.counted()));
    }

    private static FoundCounted findCounted(Method method, Class<?> beanClass) {
        var onMethod = findCountedOnElement(method);
        if (onMethod != null) {
            return new FoundCounted(method, onMethod);
        }

        Class<?> current = beanClass;
        while (current != null && current != Object.class) {
            try {
                var declared = current.getDeclaredMethod(method.getName(), method.getParameterTypes());
                var inherited = findCountedOnElement(declared);
                if (inherited != null) {
                    return new FoundCounted(declared, inherited);
                }
            } catch (NoSuchMethodException ignored) {
                // Continue with superclass traversal.
            }
            current = current.getSuperclass();
        }

        var onClass = findCountedOnElement(beanClass);
        return onClass == null ? null : new FoundCounted(method, onClass);
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

    private static Method normalizeInterceptedMethod(Class<?> beanClass, Method method) {
        var name = method.getName();
        var marker = "$$super$";
        var markerIndex = name.indexOf(marker);
        if (markerIndex < 0) {
            return method;
        }
        var targetName = name.substring(markerIndex + marker.length());
        Class<?> current = beanClass;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredMethod(targetName, method.getParameterTypes());
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return method;
    }

    private static ResolvedCounter createResolvedCounter(Class<?> beanClass, Method method, Counted counted) {
        var metricName = resolveMetricName(beanClass, method, counted);
        var tags = parseTags(counted.tags());
        var metadata = Metadata.builder()
                .withName(metricName)
                .withDescription(counted.description())
                .withUnit(counted.unit())
                .build();
        return new ResolvedCounter(new MetricID(metricName, tags), metadata, tags, normalizeScope(counted.scope()));
    }

    private static String resolveMetricName(Class<?> beanClass, Method method, Counted counted) {
        var explicitName = counted.name().trim();
        if (!explicitName.isEmpty()) {
            if (counted.absolute()) return explicitName;
            boolean classLevel = !method.isAnnotationPresent(Counted.class);
            if (classLevel) {
                var pkg = beanClass.getPackageName();
                return pkg.isEmpty()
                        ? explicitName + "." + method.getName()
                        : pkg + "." + explicitName + "." + method.getName();
            }
            return MetricRegistry.name(method.getDeclaringClass(), explicitName);
        }
        return counted.absolute()
                ? method.getName()
                : (method.isAnnotationPresent(Counted.class)
                ? MetricRegistry.name(method.getDeclaringClass(), method.getName())
                : MetricRegistry.name(beanClass, method.getName()));
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
                .map(CountedInterceptor::parseTag)
                .toArray(Tag[]::new);
    }

    private static Tag parseTag(String source) {
        var separator = source.indexOf('=');
        if (separator <= 0 || separator == source.length() - 1) {
            throw new DiracException("Invalid @Counted tag declaration: '" + source + "'");
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
            throw new DiracException("Invalid @Counted tag declaration: '" + source + "'", exception);
        }
    }

    private record CacheKey(Class<?> beanClass, Method method) {
    }

    private record ResolvedCounter(MetricID metricID, Metadata metadata, Tag[] tags, String scope) {
    }

    private record FoundCounted(Method sourceMethod, Counted counted) {
    }
}

