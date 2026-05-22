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
import org.eclipse.microprofile.metrics.annotation.Timed;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepteur CDI pour {@link Timed}.
 */
@Interceptor
@Timed
@Priority(4021)
public class TimedInterceptor {
    private final ConcurrentHashMap<CacheKey, ResolvedTimer> timers = new ConcurrentHashMap<>();

    @Inject
    MetricRegistryProducerBean registries;

    public TimedInterceptor() {
    }

    TimedInterceptor(MetricRegistryProducerBean registries) {
        this.registries = Objects.requireNonNull(registries, "registries must not be null");
    }

    @AroundConstruct
    public void aroundConstruct(InvocationContext context) throws Exception {
        var constructor = context.getConstructor();
        Class<?> beanClass = constructor.getDeclaringClass();
        if (beanClass.getName().contains("$$")) beanClass = beanClass.getSuperclass();
        var classTimed = beanClass.getAnnotation(Timed.class);
        var constructorTimed = constructor.getAnnotation(Timed.class);
        final Timed effective = constructorTimed != null ? constructorTimed : classTimed;

        if (effective != null && !java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())) {
            boolean classLevel = (constructorTimed == null);
            var metricName = classLevel
                    ? DiracExtension.resolveClassLevelConstructorMetricName(beanClass, effective)
                    : DiracExtension.resolveConstructorLevelMetricName(beanClass, effective.name(), effective.absolute());
            var tags = parseTags(effective.tags());
            var metadata = Metadata.builder()
                    .withName(metricName).withDescription(effective.description()).withUnit(effective.unit()).build();
            var scope = normalizeScope(effective.scope());
            var start = System.nanoTime();
            try {
                context.proceed();
            } finally {
                registries.registry(scope).timer(metadata, tags).update(Duration.ofNanos(System.nanoTime() - start));
            }
        } else {
            context.proceed();
        }

        Object target = context.getTarget();
        if (target == null) return;
        Class<?> actualClass = target.getClass();
        if (actualClass.getName().contains("$$")) actualClass = actualClass.getSuperclass();
        preRegisterTimers(actualClass);
    }

    void preRegisterTimers(Class<?> beanClass) {
        var classTimed = beanClass.getAnnotation(Timed.class);
        // Scan all methods including inherited ones (excluding Object methods)
        for (var method : getAllDeclaredMethods(beanClass)) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) continue;
            if (method.getName().contains("$$")) continue;
            var methodTimed = method.getAnnotation(Timed.class);
            final Timed timed = methodTimed != null ? methodTimed : classTimed;
            if (timed == null) continue;
            boolean classLevel = (methodTimed == null);
            if (classLevel && java.lang.reflect.Modifier.isPrivate(method.getModifiers())) continue;
            var resolved = timers.computeIfAbsent(new CacheKey(beanClass, method),
                    ignored -> createResolvedTimer(beanClass, method, timed));
            registries.registry(resolved.scope()).timer(resolved.metadata(), resolved.tags());
        }
    }

    /**
     * Retourne toutes les méthodes déclarées dans la classe et ses superclasses (sauf Object).
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
        var resolved = resolveTimer(context);
        if (resolved == null) {
            return context.proceed();
        }

        var registry = registries.registry(resolved.scope());
        var timer = registry.getTimer(resolved.metricID());
        if (timer == null) {
            throw new IllegalStateException("Timer metric was removed from registry: " + resolved.metricID());
        }

        var start = System.nanoTime();
        try {
            return context.proceed();
        } finally {
            var duration = Duration.ofNanos(System.nanoTime() - start);
            timer.update(duration);
            updateNonPublicInheritedTimers(context, registry);
        }
    }

    private void updateNonPublicInheritedTimers(InvocationContext context, MetricRegistry registry) {
        var invokedMethod = context.getMethod();
        if (invokedMethod == null || !invokedMethod.getName().contains("$$super$")) {
            return;
        }

        var target = context.getTarget();
        if (target == null) {
            return;
        }

        Class<?> beanClass = target.getClass();
        if (beanClass.getName().contains("$$")) {
            beanClass = beanClass.getSuperclass();
        }

        var superClass = beanClass.getSuperclass();
        if (superClass == null || superClass == Object.class) {
            return;
        }

        for (var method : superClass.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            var timed = method.getAnnotation(Timed.class);
            if (timed == null) {
                continue;
            }
            var resolved = createResolvedTimer(beanClass, method, timed);
            var timer = registry.getTimer(resolved.metricID());
            if (timer != null) {
                timer.update(Duration.ZERO);
            }
        }
    }

    private ResolvedTimer resolveTimer(InvocationContext context) {
        Objects.requireNonNull(context, "context must not be null");
        var method = Objects.requireNonNull(context.getMethod(), "InvocationContext method must not be null");
        var beanClass = context.getTarget() != null ? context.getTarget().getClass() : method.getDeclaringClass();
        if (beanClass.getName().contains("$$")) {
            beanClass = beanClass.getSuperclass();
        }
        method = normalizeInterceptedMethod(beanClass, method);
        final Class<?> resolvedBeanClass = beanClass;
        var timed = findTimed(method, beanClass);
        if (timed == null) {
            return null;
        }
        var sourceMethod = timed.sourceMethod();
        return timers.computeIfAbsent(new CacheKey(resolvedBeanClass, sourceMethod),
                ignored -> createResolvedTimer(resolvedBeanClass, sourceMethod, timed.timed()));
    }

    private static FoundTimed findTimed(Method method, Class<?> beanClass) {
        var onMethod = findTimedOnElement(method);
        if (onMethod != null) {
            return new FoundTimed(method, onMethod);
        }

        Class<?> current = beanClass;
        while (current != null && current != Object.class) {
            try {
                var declared = current.getDeclaredMethod(method.getName(), method.getParameterTypes());
                var inherited = findTimedOnElement(declared);
                if (inherited != null) {
                    return new FoundTimed(declared, inherited);
                }
            } catch (NoSuchMethodException ignored) {
                // Continue with superclass traversal.
            }
            current = current.getSuperclass();
        }

        var onClass = findTimedOnElement(beanClass);
        return onClass == null ? null : new FoundTimed(method, onClass);
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

    private static ResolvedTimer createResolvedTimer(Class<?> beanClass, Method method, Timed timed) {
        var metricName = resolveMetricName(beanClass, method, timed);
        var tags = parseTags(timed.tags());
        var metadata = Metadata.builder()
                .withName(metricName)
                .withDescription(timed.description())
                .withUnit(timed.unit())
                .build();
        return new ResolvedTimer(new MetricID(metricName, tags), metadata, tags, normalizeScope(timed.scope()));
    }

    private static String resolveMetricName(Class<?> beanClass, Method method, Timed timed) {
        var explicitName = timed.name().trim();
        if (!explicitName.isEmpty()) {
            if (timed.absolute()) return explicitName;
            boolean classLevel = !method.isAnnotationPresent(Timed.class);
            if (classLevel) {
                var pkg = beanClass.getPackageName();
                return pkg.isEmpty()
                        ? explicitName + "." + method.getName()
                        : pkg + "." + explicitName + "." + method.getName();
            }
            return MetricRegistry.name(method.getDeclaringClass(), explicitName);
        }
        return timed.absolute()
                ? method.getName()
                : (method.isAnnotationPresent(Timed.class)
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
                .map(TimedInterceptor::parseTag)
                .toArray(Tag[]::new);
    }

    private static Tag parseTag(String source) {
        var separator = source.indexOf('=');
        if (separator <= 0 || separator == source.length() - 1) {
            throw new DiracException("Invalid @Timed tag declaration: '" + source + "'");
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
            throw new DiracException("Invalid @Timed tag declaration: '" + source + "'", exception);
        }
    }

    private record CacheKey(Class<?> beanClass, Method method) {
    }

    private record ResolvedTimer(MetricID metricID, Metadata metadata, Tag[] tags, String scope) {
    }

    private record FoundTimed(Method sourceMethod, Timed timed) {
    }
}

