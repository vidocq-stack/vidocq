package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.junit.jupiter.api.Test;

/**
 * M7 — Policy-disablement tests via external configuration.
 *
 * <p>Precedence (§9 MP FT 4.1):
 * {@code <class>/<method>/<Annotation>/enabled} &gt; {@code <class>/<Annotation>/enabled}
 * &gt; {@code <Annotation>/enabled}.</p>
 */
class ConfigResolverEnabledTest {

    // --- Defaults (no external config) ---

    @Test
    void retryEnabledByDefault() {
        Method method = RetryService.class.getDeclaredMethods()[0];
        assertTrue(ConfigResolver.isRetryEnabled(method));
    }

    @Test
    void circuitBreakerEnabledByDefault() {
        assertTrue(ConfigResolver.isCircuitBreakerEnabled(CBService.class.getDeclaredMethods()[0]));
    }

    @Test
    void bulkheadEnabledByDefault() {
        assertTrue(ConfigResolver.isBulkheadEnabled(BulkheadService.class.getDeclaredMethods()[0]));
    }

    @Test
    void timeoutEnabledByDefault() {
        assertTrue(ConfigResolver.isTimeoutEnabled(TimeoutService.class.getDeclaredMethods()[0]));
    }

    @Test
    void fallbackEnabledByDefault() {
        assertTrue(ConfigResolver.isFallbackEnabled(FallbackService.class.getDeclaredMethods()[0]));
    }

    @Test
    void asynchronousEnabledByDefault() {
        // M7 §9.1: @Asynchronous is subject to the enabled flag just like the other policies.
        assertTrue(ConfigResolver.isAsynchronousEnabled(AsyncService.class.getDeclaredMethods()[0]));
    }

    // --- M7: disablement via external config (3 precedence levels) ---

    @Test
    void retryDisabledAtMethodLevel() throws Exception {
        // §9: <class>/<method>/Retry/enabled=false → retry disabled
        Method method = RetryService.class.getDeclaredMethod("method");
        String key = method.getDeclaringClass().getName() + "/" + method.getName() + "/Retry/enabled";
        Config config = new MockConfig().set(key, "false");

        assertFalse(ConfigResolver.isEnabled(method, "Retry", config));
    }

    @Test
    void retryDisabledAtClassLevel() throws Exception {
        // §9: <class>/Retry/enabled=false → retry disabled for all methods
        // Note: the class-level key is consulted only if the annotation is on the class (inherited).
        Method method = ClassLevelRetryService.class.getDeclaredMethod("method");
        String key = method.getDeclaringClass().getName() + "/Retry/enabled";
        Config config = new MockConfig().set(key, "false");

        assertFalse(ConfigResolver.isEnabled(method, "Retry", config));
    }

    @Test
    void retryDisabledAtGlobalLevel() throws Exception {
        // §9: Retry/enabled=false → retry disabled everywhere
        Method method = RetryService.class.getDeclaredMethod("method");
        Config config = new MockConfig().set("Retry/enabled", "false");

        assertFalse(ConfigResolver.isEnabled(method, "Retry", config));
    }

    @Test
    void methodLevelOverridesClassLevel() throws Exception {
        // §9: the method-level key has the highest priority
        Method method = RetryService.class.getDeclaredMethod("method");
        String classKey = method.getDeclaringClass().getName() + "/Retry/enabled";
        String methodKey = method.getDeclaringClass().getName() + "/" + method.getName() + "/Retry/enabled";

        Config config = new MockConfig()
                .set(classKey, "false")
                .set(methodKey, "true");

        assertTrue(ConfigResolver.isEnabled(method, "Retry", config));
    }

    @Test
    void classLevelOverridesGlobal() throws Exception {
        // MP FT 4.1 §9: method > class > global precedence, regardless of the actual
        // annotation placement. Without a method-level key, the class-level key wins.
        Method method = RetryService.class.getDeclaredMethod("method");
        String classKey = method.getDeclaringClass().getName() + "/Retry/enabled";

        Config config = new MockConfig()
                .set("Retry/enabled", "false")
                .set(classKey, "true");

        assertTrue(ConfigResolver.isEnabled(method, "Retry", config));
    }

    @Test
    void classLevelAppliesWhenAnnotationIsOnClass() throws Exception {
        // Class-level annotation case: the class-level key is consulted and takes precedence over global.
        Method method = ClassLevelRetryService.class.getDeclaredMethod("method");
        String classKey = method.getDeclaringClass().getName() + "/Retry/enabled";

        Config config = new MockConfig()
                .set("Retry/enabled", "false")
                .set(classKey, "true");

        assertTrue(ConfigResolver.isEnabled(method, "Retry", config));
    }

    @Test
    void methodLevelOverridesGlobalDirectly() throws Exception {
        // M7: method-level precedence > global-level (without an intermediate class-level key)
        Method method = RetryService.class.getDeclaredMethod("method");
        String methodKey = method.getDeclaringClass().getName() + "/" + method.getName() + "/Retry/enabled";

        Config config = new MockConfig()
                .set("Retry/enabled", "false")
                .set(methodKey, "true");

        assertTrue(ConfigResolver.isEnabled(method, "Retry", config));
    }

    @Test
    void asynchronousCanBeDisabledViaConfig() throws Exception {
        // M7 §9.1: if Asynchronous/enabled=false, the method is treated as synchronous.
        Method method = AsyncService.class.getDeclaredMethod("method");
        Config config = new MockConfig().set("Asynchronous/enabled", "false");

        assertFalse(ConfigResolver.isEnabled(method, "Asynchronous", config));
    }

    // --- M7: global disablement via mp.fault.tolerance.interceptor.priority ---

    @Test
    void interceptorEnabledByDefault() {
        // No external config → the interceptor is NOT globally disabled.
        assertFalse(ConfigResolver.isInterceptorGloballyDisabled(null));
        assertFalse(ConfigResolver.isInterceptorGloballyDisabled(new MockConfig()));
    }

    @Test
    void interceptorDisabledWhenPriorityIsMaxInt() {
        // M7 §9: Integer.MAX_VALUE disables the interceptor globally.
        Config config = new MockConfig()
                .set("mp.fault.tolerance.interceptor.priority", String.valueOf(Integer.MAX_VALUE));

        assertTrue(ConfigResolver.isInterceptorGloballyDisabled(config));
    }

    @Test
    void interceptorRemainsActiveForReasonablePriorities() {
        Config config = new MockConfig()
                .set("mp.fault.tolerance.interceptor.priority", "100");

        assertFalse(ConfigResolver.isInterceptorGloballyDisabled(config));
    }

    @Test
    void nonFallbackGlobalDisableDisablesRetryByDefault() throws Exception {
        Method method = RetryService.class.getDeclaredMethod("method");
        Config config = new MockConfig().set("MP_Fault_Tolerance_NonFallback_Enabled", "false");

        assertFalse(ConfigResolver.isEnabled(method, "Retry", config));
    }

    @Test
    void methodLevelEnableOverridesNonFallbackGlobalDisable() throws Exception {
        Method method = RetryService.class.getDeclaredMethod("method");
        String methodKey = method.getDeclaringClass().getName() + "/" + method.getName() + "/Retry/enabled";
        Config config = new MockConfig()
                .set("MP_Fault_Tolerance_NonFallback_Enabled", "false")
                .set(methodKey, "true");

        assertTrue(ConfigResolver.isEnabled(method, "Retry", config));
    }

    @Test
    void classLevelEnableOverridesNonFallbackGlobalDisable() throws Exception {
        Method method = RetryService.class.getDeclaredMethod("method");
        String classKey = method.getDeclaringClass().getName() + "/Retry/enabled";
        Config config = new MockConfig()
                .set("mp.fault.tolerance.nonFallback.enabled", "false")
                .set(classKey, "true");

        assertTrue(ConfigResolver.isEnabled(method, "Retry", config));
    }

    @Test
    void allPolicyTypesSupportEnabledFlag() throws Exception {
        // Verify that all policies support enabled=false.
        Method method = AllPoliciesService.class.getDeclaredMethod("method");
        String prefix = method.getDeclaringClass().getName() + "/" + method.getName() + "/";

        Config config = new MockConfig()
                .set(prefix + "Retry/enabled", "false")
                .set(prefix + "Timeout/enabled", "false")
                .set(prefix + "CircuitBreaker/enabled", "false")
                .set(prefix + "Bulkhead/enabled", "false")
                .set(prefix + "Fallback/enabled", "false");

        assertFalse(ConfigResolver.isEnabled(method, "Retry", config));
        assertFalse(ConfigResolver.isEnabled(method, "Timeout", config));
        assertFalse(ConfigResolver.isEnabled(method, "CircuitBreaker", config));
        assertFalse(ConfigResolver.isEnabled(method, "Bulkhead", config));
        assertFalse(ConfigResolver.isEnabled(method, "Fallback", config));
    }

    // --- Test services ---

    static class RetryService {
        @Retry(maxRetries = 2)
        void method() {}
    }

    @Retry(maxRetries = 2)
    static class ClassLevelRetryService {
        void method() {}
    }

    static class CBService {
        @org.eclipse.microprofile.faulttolerance.CircuitBreaker
        void method() {}
    }

    static class BulkheadService {
        @org.eclipse.microprofile.faulttolerance.Bulkhead
        void method() {}
    }

    static class TimeoutService {
        @org.eclipse.microprofile.faulttolerance.Timeout(1000)
        void method() {}
    }

    static class FallbackService {
        @org.eclipse.microprofile.faulttolerance.Fallback(fallbackMethod = "recover")
        void method() {}
        void recover() {}
    }

    static class AsyncService {
        @org.eclipse.microprofile.faulttolerance.Asynchronous
        java.util.concurrent.CompletionStage<String> method() {
            return java.util.concurrent.CompletableFuture.completedFuture("ok");
        }
    }

    static class AllPoliciesService {
        @Retry @org.eclipse.microprofile.faulttolerance.Timeout
        @org.eclipse.microprofile.faulttolerance.CircuitBreaker
        @org.eclipse.microprofile.faulttolerance.Bulkhead
        @org.eclipse.microprofile.faulttolerance.Fallback(fallbackMethod = "recover")
        void method() {}
        void recover() {}
    }

    /** Minimal mock for Config — supports only getOptionalValue(String, Class). */
    private static final class MockConfig implements Config {
        private final Map<String, String> values = new HashMap<>();

        MockConfig set(String key, String value) {
            values.put(key, value);
            return this;
        }

        @Override
        public <T> T getValue(String propertyName, Class<T> propertyType) {
            return getOptionalValue(propertyName, propertyType)
                    .orElseThrow(() -> new NoSuchElementException(propertyName));
        }

        @Override
        public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
            String raw = values.get(propertyName);
            if (raw == null) return Optional.empty();
            return Optional.of(convert(raw, propertyType));
        }

        @Override
        public org.eclipse.microprofile.config.ConfigValue getConfigValue(String propertyName) {
            // Minimal stub — not used by ConfigResolver.
            throw new UnsupportedOperationException("not used by ConfigResolver");
        }

        @Override public Iterable<String> getPropertyNames() { return new HashSet<>(values.keySet()); }
        @Override public Iterable<ConfigSource> getConfigSources() { return Set.of(); }
        @Override public <T> Optional<Converter<T>> getConverter(Class<T> forType) { return Optional.empty(); }
        @Override public <T> T unwrap(Class<T> type) { return null; }

        @SuppressWarnings("unchecked")
        private <T> T convert(String raw, Class<T> type) {
            if (type == String.class) return (T) raw;
            if (type == Integer.class) return (T) Integer.valueOf(raw);
            if (type == Long.class) return (T) Long.valueOf(raw);
            if (type == Boolean.class) return (T) Boolean.valueOf(raw);
            if (type == Double.class) return (T) Double.valueOf(raw);
            return (T) raw;
        }
    }
}


