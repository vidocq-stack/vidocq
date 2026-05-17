package io.vidocq.heisenberg.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
        return retryConfig(method, retry, loadConfig());
    }

    static RetryConfig retryConfig(Method method, Retry retry, Config config) {
        int maxRetries = getInt(config, method, Retry.class, "maxRetries").orElse(retry.maxRetries());
        long delay = getLong(config, method, Retry.class, "delay").orElse(retry.delay());
        ChronoUnit delayUnit = getChronoUnit(config, method, Retry.class, "delayUnit").orElse(retry.delayUnit());
        long maxDuration = getLong(config, method, Retry.class, "maxDuration").orElse(retry.maxDuration());
        ChronoUnit durationUnit = getChronoUnit(config, method, Retry.class, "durationUnit").orElse(retry.durationUnit());
        long jitter = getLong(config, method, Retry.class, "jitter").orElse(retry.jitter());
        ChronoUnit jitterDelayUnit = getChronoUnit(config, method, Retry.class, "jitterDelayUnit").orElse(retry.jitterDelayUnit());
        Class<? extends Throwable>[] retryOn = getThrowableArray(config, method, Retry.class, "retryOn").orElse(retry.retryOn());
        Class<? extends Throwable>[] abortOn = getThrowableArray(config, method, Retry.class, "abortOn").orElse(retry.abortOn());

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
        return timeoutConfig(method, timeout, loadConfig());
    }

    static TimeoutConfig timeoutConfig(Method method, Timeout timeout, Config config) {
        long value = getLong(config, method, Timeout.class, "value").orElse(timeout.value());
        ChronoUnit unit = getChronoUnit(config, method, Timeout.class, "unit").orElse(timeout.unit());
        return new TimeoutConfig(value, unit);
    }

    public static CircuitBreakerConfig circuitBreakerConfig(Method method, CircuitBreaker cb) {
        return circuitBreakerConfig(method, cb, loadConfig());
    }

    static CircuitBreakerConfig circuitBreakerConfig(Method method, CircuitBreaker cb, Config config) {
        int requestVolumeThreshold = getInt(config, method, CircuitBreaker.class, "requestVolumeThreshold")
                .orElse(cb.requestVolumeThreshold());
        double failureRatio = getDouble(config, method, CircuitBreaker.class, "failureRatio")
                .orElse(cb.failureRatio());
        long delay = getLong(config, method, CircuitBreaker.class, "delay").orElse(cb.delay());
        ChronoUnit delayUnit = getChronoUnit(config, method, CircuitBreaker.class, "delayUnit").orElse(cb.delayUnit());
        int successThreshold = getInt(config, method, CircuitBreaker.class, "successThreshold")
                .orElse(cb.successThreshold());
        Class<? extends Throwable>[] failOn = getThrowableArray(config, method, CircuitBreaker.class, "failOn")
                .orElse(cb.failOn());
        Class<? extends Throwable>[] skipOn = getThrowableArray(config, method, CircuitBreaker.class, "skipOn")
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
        return bulkheadConfig(method, bulkhead, loadConfig());
    }

    static BulkheadConfig bulkheadConfig(Method method, Bulkhead bulkhead, Config config) {
        int value = getInt(config, method, Bulkhead.class, "value").orElse(bulkhead.value());
        int waitingTaskQueue = getInt(config, method, Bulkhead.class, "waitingTaskQueue")
                .orElse(bulkhead.waitingTaskQueue());

        return new BulkheadConfig(value, waitingTaskQueue);
    }

    /**
     * M8 §9.1 : configuration {@code @Fallback}.
     */
    public static FallbackConfig fallbackConfig(Method method, Fallback fallback) {
        return fallbackConfig(method, fallback, loadConfig());
    }

    static FallbackConfig fallbackConfig(Method method, Fallback fallback, Config config) {
        Class<? extends Throwable>[] applyOn = getThrowableArray(config, method, Fallback.class, "applyOn")
                .orElse(fallback.applyOn());
        Class<? extends Throwable>[] skipOn = getThrowableArray(config, method, Fallback.class, "skipOn")
                .orElse(fallback.skipOn());
        String fallbackMethod = getValue(config, method, Fallback.class, "fallbackMethod", String.class)
                .orElse(fallback.fallbackMethod());
        Class<? extends FallbackHandler<?>> fallbackHandlerClass = getFallbackHandlerClass(config, method)
                .orElse(fallback.value());
        return new FallbackConfig(applyOn, skipOn, fallbackMethod, fallbackHandlerClass);
    }

    // M7: Désactivation par politique via config externe
    public static boolean isRetryEnabled(Method method) {
        return isEnabled(method, "Retry");
    }

    public static boolean isTimeoutEnabled(Method method) {
        return isEnabled(method, "Timeout");
    }

    public static boolean isCircuitBreakerEnabled(Method method) {
        return isEnabled(method, "CircuitBreaker");
    }

    public static boolean isBulkheadEnabled(Method method) {
        return isEnabled(method, "Bulkhead");
    }

    public static boolean isFallbackEnabled(Method method) {
        return isEnabled(method, "Fallback");
    }

    public static boolean isAsynchronousEnabled(Method method) {
        return isEnabled(method, "Asynchronous");
    }

    /**
     * M7 §9 : désactivation globale via {@code mp.fault.tolerance.interceptor.priority}.
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
     * M8 §9 : valeur de la priorité de l'intercepteur Fault Tolerance.
     */
    public static int interceptorPriority() {
        return interceptorPriority(loadConfig());
    }

    static int interceptorPriority(Config config) {
        if (config == null) return DEFAULT_INTERCEPTOR_PRIORITY;
        return config.getOptionalValue("mp.fault.tolerance.interceptor.priority", Integer.class)
                .orElse(DEFAULT_INTERCEPTOR_PRIORITY);
    }

    /** Défaut spec MP FT 4.1 : Platform.AFTER (4000) + 10. */
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
        return isEnabled(method, annotation, loadConfig());
    }

    static boolean isEnabled(Method method, String annotation, Config config) {
        if (isNonFallbackGloballyDisabled(config) && !"Fallback".equals(annotation)) {
            return false;
        }
        Class<? extends Annotation> annotationType = annotationTypeFor(annotation);
        return getBooleanByName(config, method, annotationType, annotation, "enabled").orElse(true);
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
            Config config, Method method, Class<? extends Annotation> annotationType, String annotationName, String parameter) {
        return getValueByName(config, method, annotationType, annotationName, parameter, String.class).map(value -> {
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
        return getValue(config, method, annotationType, parameter, Integer.class);
    }

    private static <A extends Annotation> java.util.Optional<Long> getLong(Config config, Method method, Class<A> annotationType, String parameter) {
        return getValue(config, method, annotationType, parameter, Long.class);
    }

    private static <A extends Annotation> java.util.Optional<Double> getDouble(Config config, Method method, Class<A> annotationType, String parameter) {
        return getValue(config, method, annotationType, parameter, Double.class);
    }

    private static <A extends Annotation> java.util.Optional<ChronoUnit> getChronoUnit(Config config, Method method, Class<A> annotationType, String parameter) {
        return getValue(config, method, annotationType, parameter, String.class).map(String::trim).map(ChronoUnit::valueOf);
    }

    @SuppressWarnings("unchecked")
    private static <A extends Annotation> java.util.Optional<Class<? extends Throwable>[]> getThrowableArray(
            Config config,
            Method method,
            Class<A> annotationType,
            String parameter
    ) {
        return getValue(config, method, annotationType, parameter, String.class).map(value -> {
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
            Class<A> annotationType,
            String parameter,
            Class<T> type
    ) {
        return getValueByName(config, method, annotationType, annotationType.getSimpleName(), parameter, type);
    }

    private static <T> java.util.Optional<T> getValueByName(
            Config config,
            Method method,
            Class<? extends Annotation> annotationType,
            String annotationName,
            String parameter,
            Class<T> type
    ) {
        if (config == null) {
            return java.util.Optional.empty();
        }
        for (String key : keys(method, annotationType, annotationName, parameter)) {
            java.util.Optional<T> value = config.getOptionalValue(key, type);
            if (value.isPresent()) {
                return value;
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * MP FT 4.1 §10 : la précédence dépend de l'<i>emplacement effectif</i> de l'annotation.
     * <ul>
     *   <li>Si l'annotation est directement présente sur la méthode :
     *       {@code <class>/<method>/<Annotation>/<param>} puis {@code <Annotation>/<param>}.</li>
     *   <li>Sinon (annotation héritée de la classe) :
     *       {@code <class>/<Annotation>/<param>} puis {@code <Annotation>/<param>}.</li>
     * </ul>
     * Référence : SmallRye {@code AutoConfigProcessor} —
     * {@code configKey = onMethod ? class + "/" + method : class}.
     */
    private static List<String> keys(Method method, Class<? extends Annotation> annotationType, String annotationName, String parameter) {
        String className = method.getDeclaringClass().getName();
        String methodName = method.getName();
        String globalKey = annotationName + "/" + parameter;

        boolean onMethod = annotationType != null && method.isAnnotationPresent(annotationType);
        if (onMethod) {
            return List.of(
                    className + "/" + methodName + "/" + annotationName + "/" + parameter,
                    globalKey
            );
        }
        return List.of(
                className + "/" + annotationName + "/" + parameter,
                globalKey
        );
    }

    @SuppressWarnings("unchecked")
    private static java.util.Optional<Class<? extends FallbackHandler<?>>> getFallbackHandlerClass(Config config, Method method) {
        return getValue(config, method, Fallback.class, "value", String.class).map(className -> {
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

