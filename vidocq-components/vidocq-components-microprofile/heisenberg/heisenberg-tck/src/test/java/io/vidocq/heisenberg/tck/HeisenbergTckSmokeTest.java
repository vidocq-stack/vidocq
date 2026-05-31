package io.vidocq.heisenberg.tck;

import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Asynchronous;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Smoke test M0: verifies that the MicroProfile Fault Tolerance 4.1 spec is indeed
 * on the classpath and that the main annotations are accessible.
 * Executed by the "smoke" profile (active by default) without Arquillian.
 */
class HeisenbergTckSmokeTest {

    @Test
    void mpFaultToleranceApiOnClasspath() {
        // §2 — the six annotations must be accessible
        assertNotNull(Retry.class.getAnnotation(java.lang.annotation.Retention.class),
                "@Retry must be a RUNTIME-retained annotation");
        assertNotNull(Timeout.class.getAnnotation(java.lang.annotation.Retention.class),
                "@Timeout must be a RUNTIME-retained annotation");
        assertNotNull(CircuitBreaker.class.getAnnotation(java.lang.annotation.Retention.class),
                "@CircuitBreaker must be a RUNTIME-retained annotation");
        assertNotNull(Bulkhead.class.getAnnotation(java.lang.annotation.Retention.class),
                "@Bulkhead must be a RUNTIME-retained annotation");
        assertNotNull(Fallback.class.getAnnotation(java.lang.annotation.Retention.class),
                "@Fallback must be a RUNTIME-retained annotation");
        assertNotNull(Asynchronous.class.getAnnotation(java.lang.annotation.Retention.class),
                "@Asynchronous must be a RUNTIME-retained annotation");
    }
}
