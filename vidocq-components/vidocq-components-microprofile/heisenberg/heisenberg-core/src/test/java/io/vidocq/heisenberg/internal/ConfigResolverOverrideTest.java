package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.junit.jupiter.api.Test;

/**
 * M8 §9 — Tests de surcharge complète des paramètres de politiques via MicroProfile Config.
 *
 * <p>Précédence (§9) : {@code <class>/<method>/<Annotation>/<param>}
 * &gt; {@code <class>/<Annotation>/<param>} &gt; {@code <Annotation>/<param>}.</p>
 *
 * <p>Tous les paramètres de toutes les politiques sont surchargeables, excepté ceux
 * fixés au build-time pour {@code @Fallback} ({@code value} handler, {@code fallbackMethod}).</p>
 */
class ConfigResolverOverrideTest {

    // --- @Retry : surcharge complète des 9 paramètres ---

    @Test
    void retryAllParametersOverridable() throws Exception {
        Method method = Service.class.getDeclaredMethod("retryMethod");
        TestConfig config = new TestConfig()
                .set("Retry/maxRetries", "7")
                .set("Retry/delay", "250")
                .set("Retry/delayUnit", "MILLIS")
                .set("Retry/maxDuration", "5")
                .set("Retry/durationUnit", "SECONDS")
                .set("Retry/jitter", "100")
                .set("Retry/jitterDelayUnit", "MILLIS")
                .set("Retry/retryOn", IOException.class.getName())
                .set("Retry/abortOn", IllegalArgumentException.class.getName());

        RetryConfig resolved = ConfigResolver.retryConfig(method, method.getAnnotation(Retry.class), config);

        assertEquals(7, resolved.maxRetries());
        assertEquals(250L, resolved.delay());
        assertEquals(ChronoUnit.MILLIS, resolved.delayUnit());
        assertEquals(5L, resolved.maxDuration());
        assertEquals(ChronoUnit.SECONDS, resolved.durationUnit());
        assertEquals(100L, resolved.jitter());
        assertEquals(ChronoUnit.MILLIS, resolved.jitterDelayUnit());
        assertArrayEquals(new Class[] {IOException.class}, resolved.retryOn());
        assertArrayEquals(new Class[] {IllegalArgumentException.class}, resolved.abortOn());
    }

    @Test
    void retryMethodLevelOverridesClassLevel() throws Exception {
        Method method = Service.class.getDeclaredMethod("retryMethod");
        String cls = method.getDeclaringClass().getName();
        TestConfig config = new TestConfig()
                .set(cls + "/Retry/maxRetries", "3")
                .set(cls + "/retryMethod/Retry/maxRetries", "9");

        RetryConfig resolved = ConfigResolver.retryConfig(method, method.getAnnotation(Retry.class), config);
        assertEquals(9, resolved.maxRetries());
    }

    // --- @Timeout ---

    @Test
    void timeoutAllParametersOverridable() throws Exception {
        Method method = Service.class.getDeclaredMethod("timeoutMethod");
        TestConfig config = new TestConfig()
                .set("Timeout/value", "5000")
                .set("Timeout/unit", "MILLIS");

        TimeoutConfig resolved = ConfigResolver.timeoutConfig(method, method.getAnnotation(Timeout.class), config);
        assertEquals(5000L, resolved.value());
        assertEquals(ChronoUnit.MILLIS, resolved.unit());
    }

    // --- @CircuitBreaker : surcharge complète des 7 paramètres ---

    @Test
    void circuitBreakerAllParametersOverridable() throws Exception {
        Method method = Service.class.getDeclaredMethod("cbMethod");
        TestConfig config = new TestConfig()
                .set("CircuitBreaker/requestVolumeThreshold", "42")
                .set("CircuitBreaker/failureRatio", "0.75")
                .set("CircuitBreaker/delay", "3")
                .set("CircuitBreaker/delayUnit", "SECONDS")
                .set("CircuitBreaker/successThreshold", "5")
                .set("CircuitBreaker/failOn", IOException.class.getName())
                .set("CircuitBreaker/skipOn", IllegalStateException.class.getName());

        CircuitBreakerConfig resolved = ConfigResolver.circuitBreakerConfig(
                method, method.getAnnotation(CircuitBreaker.class), config);

        assertEquals(42, resolved.requestVolumeThreshold());
        assertEquals(0.75, resolved.failureRatio());
        assertEquals(3L, resolved.delay());
        assertEquals(ChronoUnit.SECONDS, resolved.delayUnit());
        assertEquals(5, resolved.successThreshold());
        assertArrayEquals(new Class[] {IOException.class}, resolved.failOn());
        assertArrayEquals(new Class[] {IllegalStateException.class}, resolved.skipOn());
    }

    // --- @Bulkhead ---

    @Test
    void bulkheadAllParametersOverridable() throws Exception {
        Method method = Service.class.getDeclaredMethod("bulkheadMethod");
        TestConfig config = new TestConfig()
                .set("Bulkhead/value", "20")
                .set("Bulkhead/waitingTaskQueue", "100");

        BulkheadConfig resolved = ConfigResolver.bulkheadConfig(
                method, method.getAnnotation(Bulkhead.class), config);

        assertEquals(20, resolved.value());
        assertEquals(100, resolved.waitingTaskQueue());
    }

    // --- @Fallback : applyOn et skipOn surchargeables (spec §9.1) ---

    @Test
    void fallbackDefaultsFromAnnotation() throws Exception {
        Method method = Service.class.getDeclaredMethod("fallbackMethod");
        FallbackConfig resolved = ConfigResolver.fallbackConfig(
                method, method.getAnnotation(Fallback.class), new TestConfig());

        // Défauts spec : applyOn = {Throwable.class}, skipOn = {}
        assertArrayEquals(new Class[] {Throwable.class}, resolved.applyOn());
        assertEquals(0, resolved.skipOn().length);
    }

    @Test
    void fallbackApplyOnOverridable() throws Exception {
        Method method = Service.class.getDeclaredMethod("fallbackMethod");
        TestConfig config = new TestConfig()
                .set("Fallback/applyOn", IOException.class.getName());

        FallbackConfig resolved = ConfigResolver.fallbackConfig(
                method, method.getAnnotation(Fallback.class), config);

        assertArrayEquals(new Class[] {IOException.class}, resolved.applyOn());
    }

    @Test
    void fallbackSkipOnOverridable() throws Exception {
        Method method = Service.class.getDeclaredMethod("fallbackMethod");
        TestConfig config = new TestConfig()
                .set("Fallback/skipOn", IllegalArgumentException.class.getName());

        FallbackConfig resolved = ConfigResolver.fallbackConfig(
                method, method.getAnnotation(Fallback.class), config);

        assertArrayEquals(new Class[] {IllegalArgumentException.class}, resolved.skipOn());
    }

    @Test
    void fallbackApplyOnMethodLevelOverridesGlobal() throws Exception {
        Method method = Service.class.getDeclaredMethod("fallbackMethod");
        String prefix = method.getDeclaringClass().getName() + "/fallbackMethod/";
        TestConfig config = new TestConfig()
                .set("Fallback/applyOn", IOException.class.getName())
                .set(prefix + "Fallback/applyOn", IllegalStateException.class.getName());

        FallbackConfig resolved = ConfigResolver.fallbackConfig(
                method, method.getAnnotation(Fallback.class), config);

        assertArrayEquals(new Class[] {IllegalStateException.class}, resolved.applyOn());
    }

    @Test
    void fallbackShouldApplyHonorsSkipOnPrecedence() {
        FallbackConfig cfg = new FallbackConfig(
                new Class[] {Throwable.class},
                new Class[] {IllegalArgumentException.class},
                "recover",
                org.eclipse.microprofile.faulttolerance.Fallback.DEFAULT.class
        );
        // skipOn match → pas de fallback même si applyOn match aussi
        assertFalse(cfg.shouldApplyFallback(new IllegalArgumentException()));
        assertTrue(cfg.shouldApplyFallback(new IOException()));
    }

    // --- Test services ---

    static class Service {
        @Retry void retryMethod() {}
        @Timeout void timeoutMethod() {}
        @CircuitBreaker void cbMethod() {}
        @Bulkhead void bulkheadMethod() {}
        @Fallback(fallbackMethod = "recover") void fallbackMethod() {}
        void recover() {}
    }
}

