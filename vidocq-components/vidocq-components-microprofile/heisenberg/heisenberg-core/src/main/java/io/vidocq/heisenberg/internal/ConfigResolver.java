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
package io.vidocq.heisenberg.internal;

import java.lang.annotation.Annotation;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.faulttolerance.Asynchronous;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.FallbackHandler;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;

public final class ConfigResolver {

    private ConfigResolver() {}

    public static RetryConfig retryConfig(Method method, Retry retry) {
        return retryConfig(method, method.getDeclaringClass(), retry, loadConfig());
    }

    public static RetryConfig retryConfig(Method method, Class<?> beanClass, Retry retry) {
        return retryConfig(method, beanClass, retry, loadConfig());
    }

    static RetryConfig retryConfig(Method method, Retry retry, Config config) {
        return retryConfig(method, method.getDeclaringClass(), retry, config);
    }

    static RetryConfig retryConfig(Method method, Class<?> beanClass, Retry retry, Config config) {
        int maxRetries = getInt(config, method, beanClass, Retry.class, "maxRetries").orElse(retry.maxRetries());
        long delay = getLong(config, method, beanClass, Retry.class, "delay").orElse(retry.delay());
        ChronoUnit delayUnit = getChronoUnit(config, method, beanClass, Retry.class, "delayUnit").orElse(retry.delayUnit());
        long maxDuration = getLong(config, method, beanClass, Retry.class, "maxDuration").orElse(retry.maxDuration());
        ChronoUnit durationUnit = getChronoUnit(config, method, beanClass, Retry.class, "durationUnit").orElse(retry.durationUnit());
        long jitter = getLong(config, method, beanClass, Retry.class, "jitter").orElse(retry.jitter());
        ChronoUnit jitterDelayUnit = getChronoUnit(config, method, beanClass, Retry.class, "jitterDelayUnit").orElse(retry.jitterDelayUnit());
        Class<? extends Throwable>[] retryOn = getThrowableArray(config, method, beanClass, Retry.class, "retryOn").orElse(retry.retryOn());
        Class<? extends Throwable>[] abortOn = getThrowableArray(config, method, beanClass, Retry.class, "abortOn").orElse(retry.abortOn());

        return new RetryConfig(
                maxRetries,
                delay,
                delayUnit,
                maxDuration,
                durationUnit,
                jitter,
                jitterDelayUnit,
                retryOn,
                abortOn
        );
    }

    public static TimeoutConfig timeoutConfig(Method method, Timeout timeout) {
        return timeoutConfig(method, method.getDeclaringClass(), timeout, loadConfig());
    }

    public static TimeoutConfig timeoutConfig(Method method, Class<?> beanClass, Timeout timeout) {
        return timeoutConfig(method, beanClass, timeout, loadConfig());
    }

    static TimeoutConfig timeoutConfig(Method method, Timeout timeout, Config config) {
        return timeoutConfig(method, method.getDeclaringClass(), timeout, config);
    }

    static TimeoutConfig timeoutConfig(Method method, Class<?> beanClass, Timeout timeout, Config config) {
        long value = getLong(config, method, beanClass, Timeout.class, "value").orElse(timeout.value());
        ChronoUnit unit = getChronoUnit(config, method, beanClass, Timeout.class, "unit").orElse(timeout.unit());
        return new TimeoutConfig(value, unit);
    }

    public static CircuitBreakerConfig circuitBreakerConfig(Method method, CircuitBreaker cb) {
        return circuitBreakerConfig(method, method.getDeclaringClass(), cb, loadConfig());
    }

    public static CircuitBreakerConfig circuitBreakerConfig(Method method, Class<?> beanClass, CircuitBreaker cb) {
        return circuitBreakerConfig(method, beanClass, cb, loadConfig());
    }

    static CircuitBreakerConfig circuitBreakerConfig(Method method, CircuitBreaker cb, Config config) {
        return circuitBreakerConfig(method, method.getDeclaringClass(), cb, config);
    }

    static CircuitBreakerConfig circuitBreakerConfig(Method method, Class<?> beanClass, CircuitBreaker cb, Config config) {
        int requestVolumeThreshold = getInt(config, method, beanClass, CircuitBreaker.class, "requestVolumeThreshold")
                .orElse(cb.requestVolumeThreshold());
        double failureRatio = getDouble(config, method, beanClass, CircuitBreaker.class, "failureRatio")
                .orElse(cb.failureRatio());
        long delay = getLong(config, method, beanClass, CircuitBreaker.class, "delay").orElse(cb.delay());
        ChronoUnit delayUnit = getChronoUnit(config, method, beanClass, CircuitBreaker.class, "delayUnit").orElse(cb.delayUnit());
        int successThreshold = getInt(config, method, beanClass, CircuitBreaker.class, "successThreshold")
                .orElse(cb.successThreshold());
        Class<? extends Throwable>[] failOn = getThrowableArray(config, method, beanClass, CircuitBreaker.class, "failOn")
                .orElse(cb.failOn());
        Class<? extends Throwable>[] skipOn = getThrowableArray(config, method, beanClass, CircuitBreaker.class, "skipOn")
                .orElse(cb.skipOn());

        return new CircuitBreakerConfig(
                requestVolumeThreshold,
                failureRatio,
                delay,
                delayUnit,
                successThreshold,
                failOn,
                skipOn
        );
    }

    public static BulkheadConfig bulkheadConfig(Method method, Bulkhead bulkhead) {
        return bulkheadConfig(method, method.getDeclaringClass(), bulkhead, loadConfig());
    }

    public static BulkheadConfig bulkheadConfig(Method method, Class<?> beanClass, Bulkhead bulkhead) {
        return bulkheadConfig(method, beanClass, bulkhead, loadConfig());
    }

    static BulkheadConfig bulkheadConfig(Method method, Bulkhead bulkhead, Config config) {
        return bulkheadConfig(method, method.getDeclaringClass(), bulkhead, config);
    }

    static BulkheadConfig bulkheadConfig(Method method, Class<?> beanClass, Bulkhead bulkhead, Config config) {
        int value = getInt(config, method, beanClass, Bulkhead.class, "value").orElse(bulkhead.value());
        int waitingTaskQueue = getInt(config, method, beanClass, Bulkhead.class, "waitingTaskQueue")
                .orElse(bulkhead.waitingTaskQueue());

        return new BulkheadConfig(value, waitingTaskQueue);
    }

    /**
     * M8 §9.1: {@code @Fallback} configuration.
     */
    public static FallbackConfig fallbackConfig(Method method, Fallback fallback) {
        return fallbackConfig(method, method.getDeclaringClass(), fallback, loadConfig());
    }

    public static FallbackConfig fallbackConfig(Method method, Class<?> beanClass, Fallback fallback) {
        return fallbackConfig(method, beanClass, fallback, loadConfig());
    }

    static FallbackConfig fallbackConfig(Method method, Fallback fallback, Config config) {
        return fallbackConfig(method, method.getDeclaringClass(), fallback, config);
    }

    static FallbackConfig fallbackConfig(Method method, Class<?> beanClass, Fallback fallback, Config config) {
        Class<? extends Throwable>[] applyOn = getThrowableArray(config, method, beanClass, Fallback.class, "applyOn")
                .orElse(fallback.applyOn());
        Class<? extends Throwable>[] skipOn = getThrowableArray(config, method, beanClass, Fallback.class, "skipOn")
                .orElse(fallback.skipOn());
        String fallbackMethod = getValue(config, method, beanClass, Fallback.class, "fallbackMethod", String.class)
                .orElse(fallback.fallbackMethod());
        Class<? extends FallbackHandler<?>> fallbackHandlerClass = getFallbackHandlerClass(config, method, beanClass)
                .orElse(fallback.value());
        return new FallbackConfig(applyOn, skipOn, fallbackMethod, fallbackHandlerClass);
    }

    // M7: policy-based disablement via external configuration
    public static boolean isRetryEnabled(Method method) {
        return isEnabled(method, method.getDeclaringClass(), "Retry");
    }

    public static boolean isRetryEnabled(Method method, Class<?> beanClass) {
        return isEnabled(method, beanClass, "Retry");
    }

    public static boolean isTimeoutEnabled(Method method) {
        return isEnabled(method, method.getDeclaringClass(), "Timeout");
    }

    public static boolean isTimeoutEnabled(Method method, Class<?> beanClass) {
        return isEnabled(method, beanClass, "Timeout");
    }

    public static boolean isCircuitBreakerEnabled(Method method) {
        return isEnabled(method, method.getDeclaringClass(), "CircuitBreaker");
    }

    public static boolean isCircuitBreakerEnabled(Method method, Class<?> beanClass) {
        return isEnabled(method, beanClass, "CircuitBreaker");
    }

    public static boolean isBulkheadEnabled(Method method) {
        return isEnabled(method, method.getDeclaringClass(), "Bulkhead");
    }

    public static boolean isBulkheadEnabled(Method method, Class<?> beanClass) {
        return isEnabled(method, beanClass, "Bulkhead");
    }

    public static boolean isFallbackEnabled(Method method) {
        return isEnabled(method, method.getDeclaringClass(), "Fallback");
    }

    public static boolean isFallbackEnabled(Method method, Class<?> beanClass) {
        return isEnabled(method, beanClass, "Fallback");
    }

    public static boolean isAsynchronousEnabled(Method method) {
        return isEnabled(method, method.getDeclaringClass(), "Asynchronous");
    }

    public static boolean isAsynchronousEnabled(Method method, Class<?> beanClass) {
        return isEnabled(method, beanClass, "Asynchronous");
    }

    /**
     * M7 §9: global disablement via {@code mp.fault.tolerance.interceptor.priority}.
     */
    public static boolean isInterceptorGloballyDisabled() {
        return isInterceptorGloballyDisabled(loadConfig());
    }

    static boolean isInterceptorGloballyDisabled(Config config) {
        if (config == null) return false;
        return config.getOptionalValue("mp.fault.tolerance.interceptor.priority", Integer.class)
                .map(priority -> priority >= Integer.MAX_VALUE)
                .orElse(false);
    }

    /**
     * M8 §9: Fault Tolerance interceptor priority value.
     */
    public static int interceptorPriority() {
        return interceptorPriority(loadConfig());
    }

    static int interceptorPriority(Config config) {
        if (config != null) {
            Optional<Integer> fromConfig = config.getOptionalValue("mp.fault.tolerance.interceptor.priority", Integer.class);
            if (fromConfig.isPresent()) {
                return fromConfig.get();
            }
        }
        // Bootstrap fallback: during BCE initialization, MP Config may not be fully
        // initialized yet even though microprofile-config.properties is already on the classpath.
        return interceptorPriorityFromClasspath().orElse(DEFAULT_INTERCEPTOR_PRIORITY);
    }

    private static Optional<Integer> interceptorPriorityFromClasspath() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = ConfigResolver.class.getClassLoader();
        }
        if (cl == null) {
            return Optional.empty();
        }
        try {
            Enumeration<java.net.URL> resources = cl.getResources("META-INF/microprofile-config.properties");
            while (resources.hasMoreElements()) {
                java.net.URL resource = resources.nextElement();
                try (InputStream in = resource.openStream()) {
                    Properties props = new Properties();
                    props.load(in);
                    String raw = props.getProperty("mp.fault.tolerance.interceptor.priority");
                    if (raw != null && !raw.trim().isEmpty()) {
                        return Optional.of(Integer.parseInt(raw.trim()));
                    }
                } catch (Exception ignored) {
                    // Ignore malformed resources and continue scanning the classpath.
                }
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** MP FT 4.1 spec default: Platform.AFTER (4000) + 10. */
    public static final int DEFAULT_INTERCEPTOR_PRIORITY = 4010;

    public static boolean isMetricsEnabled() {
        return isMetricsEnabled(loadConfig());
    }

    static boolean isMetricsEnabled(Config config) {
        if (config == null) return true;
        return config.getOptionalValue("mp.fault.tolerance.metrics.enabled", Boolean.class)
                .orElse(true);
    }

    private static boolean isEnabled(Method method, String annotation) {
        return isEnabled(method, method.getDeclaringClass(), annotation, loadConfig());
    }

    private static boolean isEnabled(Method method, Class<?> beanClass, String annotation) {
        return isEnabled(method, beanClass, annotation, loadConfig());
    }

    static boolean isEnabled(Method method, String annotation, Config config) {
        return isEnabled(method, method.getDeclaringClass(), annotation, config);
    }

    static boolean isEnabled(Method method, Class<?> beanClass, String annotation, Config config) {
        Class<? extends Annotation> annotationType = annotationTypeFor(annotation);
        java.util.Optional<Boolean> explicit = getBooleanByName(config, method, beanClass, annotationType, annotation, "enabled");
        if (explicit.isPresent()) {
            return explicit.get();
        }
        if (isNonFallbackGloballyDisabled(config) && !"Fallback".equals(annotation)) {
            return false;
        }
        return true;
    }

    private static Class<? extends Annotation> annotationTypeFor(String simpleName) {
        switch (simpleName) {
            case "Retry": return Retry.class;
            case "Timeout": return Timeout.class;
            case "CircuitBreaker": return CircuitBreaker.class;
            case "Bulkhead": return Bulkhead.class;
            case "Fallback": return Fallback.class;
            case "Asynchronous": return Asynchronous.class;
            default: return null;
        }
    }

    private static boolean isNonFallbackGloballyDisabled(Config config) {
        if (config == null) return false;
        java.util.Optional<Boolean> value = config.getOptionalValue("MP_Fault_Tolerance_NonFallback_Enabled", Boolean.class);
        if (value.isEmpty()) {
            value = config.getOptionalValue("mp.fault.tolerance.nonFallback.enabled", Boolean.class);
        }
        return value.map(v -> !v).orElse(false);
    }

    private static java.util.Optional<Boolean> getBooleanByName(
            Config config, Method method, Class<?> beanClass, Class<? extends Annotation> annotationType, String annotationName, String parameter) {
        return getValueByName(config, method, beanClass, annotationType, annotationName, parameter, String.class).map(value -> {
            String trimmed = value.trim().toLowerCase();
            return "true".equals(trimmed);
        });
    }

    private static Config loadConfig() {
        try {
            return ConfigProvider.getConfig();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static <A extends Annotation> java.util.Optional<Integer> getInt(Config config, Method method, Class<A> annotationType, String parameter) {
        return getValue(config, method, method.getDeclaringClass(), annotationType, parameter, Integer.class);
    }

    private static <A extends Annotation> java.util.Optional<Integer> getInt(
            Config config, Method method, Class<?> beanClass, Class<A> annotationType, String parameter) {
        return getValue(config, method, beanClass, annotationType, parameter, Integer.class);
    }

    private static <A extends Annotation> java.util.Optional<Long> getLong(Config config, Method method, Class<A> annotationType, String parameter) {
        return getValue(config, method, method.getDeclaringClass(), annotationType, parameter, Long.class);
    }

    private static <A extends Annotation> java.util.Optional<Long> getLong(
            Config config, Method method, Class<?> beanClass, Class<A> annotationType, String parameter) {
        return getValue(config, method, beanClass, annotationType, parameter, Long.class);
    }

    private static <A extends Annotation> java.util.Optional<Double> getDouble(Config config, Method method, Class<A> annotationType, String parameter) {
        return getValue(config, method, method.getDeclaringClass(), annotationType, parameter, Double.class);
    }

    private static <A extends Annotation> java.util.Optional<Double> getDouble(
            Config config, Method method, Class<?> beanClass, Class<A> annotationType, String parameter) {
        return getValue(config, method, beanClass, annotationType, parameter, Double.class);
    }

    private static <A extends Annotation> java.util.Optional<ChronoUnit> getChronoUnit(Config config, Method method, Class<A> annotationType, String parameter) {
        return getValue(config, method, method.getDeclaringClass(), annotationType, parameter, String.class).map(String::trim).map(ChronoUnit::valueOf);
    }

    private static <A extends Annotation> java.util.Optional<ChronoUnit> getChronoUnit(
            Config config, Method method, Class<?> beanClass, Class<A> annotationType, String parameter) {
        return getValue(config, method, beanClass, annotationType, parameter, String.class).map(String::trim).map(ChronoUnit::valueOf);
    }

    @SuppressWarnings("unchecked")
    private static <A extends Annotation> java.util.Optional<Class<? extends Throwable>[]> getThrowableArray(
            Config config,
            Method method,
            Class<A> annotationType,
            String parameter
    ) {
        return getThrowableArray(config, method, method.getDeclaringClass(), annotationType, parameter);
    }

    @SuppressWarnings("unchecked")
    private static <A extends Annotation> java.util.Optional<Class<? extends Throwable>[]> getThrowableArray(
            Config config,
            Method method,
            Class<?> beanClass,
            Class<A> annotationType,
            String parameter
    ) {
        return getValue(config, method, beanClass, annotationType, parameter, String.class).map(value -> {
            String[] classNames = value.split(",");
            List<Class<? extends Throwable>> resolved = new ArrayList<>();
            for (String className : classNames) {
                String trimmed = className.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    Class<?> clazz = Class.forName(trimmed);
                    if (!Throwable.class.isAssignableFrom(clazz)) {
                        throw new IllegalArgumentException("Not a throwable type: " + trimmed);
                    }
                    resolved.add((Class<? extends Throwable>) clazz);
                } catch (ClassNotFoundException failure) {
                    throw new IllegalArgumentException("Unknown throwable class: " + trimmed, failure);
                }
            }
            return resolved.toArray(new Class[0]);
        });
    }

    private static <A extends Annotation, T> java.util.Optional<T> getValue(
            Config config,
            Method method,
            Class<?> beanClass,
            Class<A> annotationType,
            String parameter,
            Class<T> type
    ) {
        return getValueByName(config, method, beanClass, annotationType, annotationType.getSimpleName(), parameter, type);
    }

    private static <T> java.util.Optional<T> getValueByName(
            Config config,
            Method method,
            Class<?> beanClass,
            Class<? extends Annotation> annotationType,
            String annotationName,
            String parameter,
            Class<T> type
    ) {
        if (config == null) {
            return java.util.Optional.empty();
        }
        boolean classLevelAppliesToAllMethods = "enabled".equals(parameter);
        for (String key : keys(method, beanClass, annotationType, annotationName, parameter, classLevelAppliesToAllMethods)) {
            java.util.Optional<T> value = config.getOptionalValue(key, type);
            if (value.isPresent()) {
                return value;
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * MP FT 4.1 §9: property-resolution precedence.
     * <ul>
     *   <li>{@code <class>/<method>/<Annotation>/<param>}</li>
     *   <li>{@code <class>/<Annotation>/<param>}</li>
     *   <li>{@code <Annotation>/<param>}</li>
     * </ul>
     */
    private static List<String> keys(
            Method method,
            Class<?> beanClass,
            Class<? extends Annotation> annotationType,
            String annotationName,
            String parameter,
            boolean classLevelAppliesToAllMethods
    ) {
        String className = beanClass.getName();
        String methodName = method.getName();
        String globalKey = annotationName + "/" + parameter;
        List<String> keys = new ArrayList<>(3);
        keys.add(className + "/" + methodName + "/" + annotationName + "/" + parameter);

        boolean hasMethodLevelAnnotation = annotationType != null && method.isAnnotationPresent(annotationType);
        boolean hasClassLevelAnnotation = annotationType != null && beanClass.isAnnotationPresent(annotationType);
        if (classLevelAppliesToAllMethods || (!hasMethodLevelAnnotation && hasClassLevelAnnotation)) {
            keys.add(className + "/" + annotationName + "/" + parameter);
        }

        keys.add(globalKey);
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Optional<Class<? extends FallbackHandler<?>>> getFallbackHandlerClass(Config config, Method method) {
        return getFallbackHandlerClass(config, method, method.getDeclaringClass());
    }

    @SuppressWarnings("unchecked")
    private static java.util.Optional<Class<? extends FallbackHandler<?>>> getFallbackHandlerClass(
            Config config,
            Method method,
            Class<?> beanClass
    ) {
        return getValue(config, method, beanClass, Fallback.class, "value", String.class).map(className -> {
            try {
                Class<?> clazz = Class.forName(className.trim());
                if (!FallbackHandler.class.isAssignableFrom(clazz)) {
                    throw new IllegalArgumentException("Configured fallback value is not a FallbackHandler: " + className);
                }
                return (Class<? extends FallbackHandler<?>>) clazz;
            } catch (ClassNotFoundException failure) {
                throw new IllegalArgumentException("Unknown fallback handler class: " + className, failure);
            }
        });
    }
}

