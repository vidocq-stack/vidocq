package io.vidocq.heisenberg.internal;

import java.lang.reflect.Method;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;

public final class ConfigResolver {

    private ConfigResolver() {}

    public static RetryConfig retryConfig(Method method, Retry retry) {
        return retryConfig(method, retry, loadConfig());
    }

    static RetryConfig retryConfig(Method method, Retry retry, Config config) {
        int maxRetries = getInt(config, method, "Retry", "maxRetries").orElse(retry.maxRetries());
        long delay = getLong(config, method, "Retry", "delay").orElse(retry.delay());
        ChronoUnit delayUnit = getChronoUnit(config, method, "Retry", "delayUnit").orElse(retry.delayUnit());
        long maxDuration = getLong(config, method, "Retry", "maxDuration").orElse(retry.maxDuration());
        ChronoUnit durationUnit = getChronoUnit(config, method, "Retry", "durationUnit").orElse(retry.durationUnit());
        long jitter = getLong(config, method, "Retry", "jitter").orElse(retry.jitter());
        ChronoUnit jitterDelayUnit = getChronoUnit(config, method, "Retry", "jitterDelayUnit").orElse(retry.jitterDelayUnit());
        Class<? extends Throwable>[] retryOn = getThrowableArray(config, method, "Retry", "retryOn").orElse(retry.retryOn());
        Class<? extends Throwable>[] abortOn = getThrowableArray(config, method, "Retry", "abortOn").orElse(retry.abortOn());

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
        long value = getLong(config, method, "Timeout", "value").orElse(timeout.value());
        ChronoUnit unit = getChronoUnit(config, method, "Timeout", "unit").orElse(timeout.unit());
        return new TimeoutConfig(value, unit);
    }

    public static CircuitBreakerConfig circuitBreakerConfig(Method method, CircuitBreaker cb) {
        return circuitBreakerConfig(method, cb, loadConfig());
    }

    static CircuitBreakerConfig circuitBreakerConfig(Method method, CircuitBreaker cb, Config config) {
        int requestVolumeThreshold = getInt(config, method, "CircuitBreaker", "requestVolumeThreshold")
                .orElse(cb.requestVolumeThreshold());
        double failureRatio = getDouble(config, method, "CircuitBreaker", "failureRatio")
                .orElse(cb.failureRatio());
        long delay = getLong(config, method, "CircuitBreaker", "delay").orElse(cb.delay());
        ChronoUnit delayUnit = getChronoUnit(config, method, "CircuitBreaker", "delayUnit").orElse(cb.delayUnit());
        int successThreshold = getInt(config, method, "CircuitBreaker", "successThreshold")
                .orElse(cb.successThreshold());
        Class<? extends Throwable>[] failOn = getThrowableArray(config, method, "CircuitBreaker", "failOn")
                .orElse(cb.failOn());
        Class<? extends Throwable>[] skipOn = getThrowableArray(config, method, "CircuitBreaker", "skipOn")
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
        int value = getInt(config, method, "Bulkhead", "value").orElse(bulkhead.value());
        int waitingTaskQueue = getInt(config, method, "Bulkhead", "waitingTaskQueue")
                .orElse(bulkhead.waitingTaskQueue());

        return new BulkheadConfig(value, waitingTaskQueue);
    }

    /**
     * M8 §9.1 : configuration {@code @Fallback}. Seuls {@code applyOn} et {@code skipOn}
     * sont surchargeables ({@code value} handler et {@code fallbackMethod} sont fixés
     * à la compilation pour permettre la validation au démarrage du container).
     */
    public static FallbackConfig fallbackConfig(Method method, Fallback fallback) {
        return fallbackConfig(method, fallback, loadConfig());
    }

    static FallbackConfig fallbackConfig(Method method, Fallback fallback, Config config) {
        Class<? extends Throwable>[] applyOn = getThrowableArray(config, method, "Fallback", "applyOn")
                .orElse(fallback.applyOn());
        Class<? extends Throwable>[] skipOn = getThrowableArray(config, method, "Fallback", "skipOn")
                .orElse(fallback.skipOn());
        return new FallbackConfig(applyOn, skipOn);
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
     * Lorsque la valeur est {@code >= Integer.MAX_VALUE}, l'intercepteur Fault Tolerance
     * est désactivé pour toutes les méthodes.
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
     * Conformément à la spec, la propriété est lue <strong>une seule fois</strong> au
     * démarrage du container (BCE). Défaut : {@link jakarta.interceptor.Interceptor.Priority#PLATFORM_AFTER}
     * + 10 = 4010.
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

    /**
     * M8 §9 : indique si l'intégration metrics (MicroProfile Metrics) doit être active.
     * Défaut : {@code true} (mais stub no-op si MicroProfile Metrics n'est pas dans le classpath).
     */
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
        // Précédence : <class>/<method>/<Annotation>/enabled > <class>/<Annotation>/enabled > <Annotation>/enabled
        // Défaut : true (politique activée sauf si config externalisée dit enabled=false)
        return getBoolean(config, method, annotation, "enabled").orElse(true);
    }

    private static java.util.Optional<Boolean> getBoolean(Config config, Method method, String annotation, String parameter) {
        return getValue(config, method, annotation, parameter, String.class).map(value -> {
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

    private static java.util.Optional<Integer> getInt(Config config, Method method, String annotation, String parameter) {
        return getValue(config, method, annotation, parameter, Integer.class);
    }

    private static java.util.Optional<Long> getLong(Config config, Method method, String annotation, String parameter) {
        return getValue(config, method, annotation, parameter, Long.class);
    }

    private static java.util.Optional<Double> getDouble(Config config, Method method, String annotation, String parameter) {
        return getValue(config, method, annotation, parameter, Double.class);
    }

    private static java.util.Optional<ChronoUnit> getChronoUnit(Config config, Method method, String annotation, String parameter) {
        return getValue(config, method, annotation, parameter, String.class).map(String::trim).map(ChronoUnit::valueOf);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Optional<Class<? extends Throwable>[]> getThrowableArray(
            Config config,
            Method method,
            String annotation,
            String parameter
    ) {
        return getValue(config, method, annotation, parameter, String.class).map(value -> {
            String[] classNames = value.split(",");
            List<Class<? extends Throwable>> resolved = new ArrayList<>();
            for (String className : classNames) {
                String trimmed = className.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
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

    private static <T> java.util.Optional<T> getValue(
            Config config,
            Method method,
            String annotation,
            String parameter,
            Class<T> type
    ) {
        if (config == null) {
            return java.util.Optional.empty();
        }

        for (String key : keys(method, annotation, parameter)) {
            java.util.Optional<T> value = config.getOptionalValue(key, type);
            if (value.isPresent()) {
                return value;
            }
        }

        return java.util.Optional.empty();
    }

    private static List<String> keys(Method method, String annotation, String parameter) {
        String className = method.getDeclaringClass().getName();
        String methodName = method.getName();
        return List.of(
                className + "/" + methodName + "/" + annotation + "/" + parameter,
                className + "/" + annotation + "/" + parameter,
                annotation + "/" + parameter
        );
    }
}


