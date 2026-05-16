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
 * Smoke test M0 : vérifie que la spec MicroProfile Fault Tolerance 4.1 est bien
 * sur le classpath et que les annotations principales sont accessibles.
 * Exécuté par le profil "smoke" (actif par défaut) sans Arquillian.
 */
class HeisenbergTckSmokeTest {

    @Test
    void mpFaultToleranceApiOnClasspath() {
        // §2 — les six annotations doivent être accessibles
        assertNotNull(Retry.class.getAnnotation(java.lang.annotation.Retention.class),
                "@Retry doit être une annotation de rétention RUNTIME");
        assertNotNull(Timeout.class.getAnnotation(java.lang.annotation.Retention.class),
                "@Timeout doit être une annotation de rétention RUNTIME");
        assertNotNull(CircuitBreaker.class.getAnnotation(java.lang.annotation.Retention.class),
                "@CircuitBreaker doit être une annotation de rétention RUNTIME");
        assertNotNull(Bulkhead.class.getAnnotation(java.lang.annotation.Retention.class),
                "@Bulkhead doit être une annotation de rétention RUNTIME");
        assertNotNull(Fallback.class.getAnnotation(java.lang.annotation.Retention.class),
                "@Fallback doit être une annotation de rétention RUNTIME");
        assertNotNull(Asynchronous.class.getAnnotation(java.lang.annotation.Retention.class),
                "@Asynchronous doit être une annotation de rétention RUNTIME");
    }
}
