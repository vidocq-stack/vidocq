package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.junit.jupiter.api.Test;

/**
 * M8 §9 — Integration tests: effective resolution goes through
 * {@code ConfigProvider.getConfig()} (Ravel), which automatically loads the
 * {@code META-INF/microprofile-config.properties} file from the test classpath.
 *
 * <p>Covers the three precedence levels defined by the spec:
 * {@code <class>/<method>/<Annotation>/<param>} &gt; {@code <class>/<Annotation>/<param>}
 * &gt; {@code <Annotation>/<param>}.</p>
 */
class ConfigResolverIntegrationTest {

    @Test
    void retryGlobalOverrideFromPropertiesFile() throws Exception {
        // <FQCN>/retryCall/Retry/maxRetries=5 (method-level so other tests are not polluted)
        Method method = DemoService.class.getDeclaredMethod("retryCall");
        RetryConfig resolved = ConfigResolver.retryConfig(method, method.getAnnotation(Retry.class));
        assertEquals(5, resolved.maxRetries(), "method-level override Retry/maxRetries=5 must apply");
    }

    @Test
    void timeoutClassLevelOverrideFromPropertiesFile() throws Exception {
        // <FQCN>/Timeout/value=3000 in microprofile-config.properties
        // @Timeout is on the DemoService class → the class-level key applies.
        Method method = DemoService.class.getDeclaredMethod("genericCall");
        TimeoutConfig resolved = ConfigResolver.timeoutConfig(method, DemoService.class.getAnnotation(Timeout.class));
        assertEquals(3000L, resolved.value(), "class-level override must apply");
    }

    @Test
    void circuitBreakerMethodLevelOverrideFromPropertiesFile() throws Exception {
        // <FQCN>/criticalCall/CircuitBreaker/requestVolumeThreshold=42
        Method method = DemoService.class.getDeclaredMethod("criticalCall");
        CircuitBreakerConfig resolved = ConfigResolver.circuitBreakerConfig(
                method, method.getAnnotation(CircuitBreaker.class));
        assertEquals(42, resolved.requestVolumeThreshold(), "method-level override must apply");
        assertEquals(0.9, resolved.failureRatio(), 1e-9);
    }

    @Test
    void bulkheadGlobalOverrideFromPropertiesFile() throws Exception {
        // method-level so other tests are not polluted
        Method method = DemoService.class.getDeclaredMethod("bulkheadCall");
        BulkheadConfig resolved = ConfigResolver.bulkheadConfig(
                method, method.getAnnotation(Bulkhead.class));
        assertEquals(15, resolved.value());
    }

    @Test
    void fallbackApplyOnGlobalOverrideFromPropertiesFile() throws Exception {
        Method method = DemoService.class.getDeclaredMethod("fallbackCall");
        FallbackConfig resolved = ConfigResolver.fallbackConfig(
                method, method.getAnnotation(Fallback.class));
        assertArrayEquals(new Class[] {IOException.class}, resolved.applyOn());
    }

    @Test
    void fallbackGloballyDisabledFromPropertiesFile() throws Exception {
        // Fallback/enabled=false → disabled
        Method method = DemoService.class.getDeclaredMethod("fallbackCall");
        assertFalse(ConfigResolver.isFallbackEnabled(method));
    }

    @Test
    void interceptorPriorityReadFromPropertiesFile() {
        // mp.fault.tolerance.interceptor.priority=4500
        assertEquals(4500, ConfigResolver.interceptorPriority());
    }

    @Test
    void interceptorNotGloballyDisabledAtReasonablePriority() {
        // 4500 < Integer.MAX_VALUE → interceptor active
        assertFalse(ConfigResolver.isInterceptorGloballyDisabled());
    }

    @Test
    void metricsCanBeDisabledFromPropertiesFile() {
        // mp.fault.tolerance.metrics.enabled=false
        assertFalse(ConfigResolver.isMetricsEnabled());
    }

    @Test
    void otherPoliciesRemainEnabledByDefault() throws Exception {
        Method method = DemoService.class.getDeclaredMethod("retryCall");
        assertTrue(ConfigResolver.isRetryEnabled(method));
        assertTrue(ConfigResolver.isTimeoutEnabled(method));
        assertTrue(ConfigResolver.isCircuitBreakerEnabled(method));
        assertTrue(ConfigResolver.isBulkheadEnabled(method));
    }

    // --- Test service ---

    // @Timeout at class level → the <FQCN>/Timeout/value=3000 (class-level) key applies.
    @Timeout
    static class DemoService {
        @Retry void retryCall() {}
        void genericCall() {} // inherits @Timeout from the class
        @CircuitBreaker void criticalCall() {}
        @Bulkhead void bulkheadCall() {}
        @Fallback(fallbackMethod = "recover") void fallbackCall() {}
        void recover() {}
    }
}



