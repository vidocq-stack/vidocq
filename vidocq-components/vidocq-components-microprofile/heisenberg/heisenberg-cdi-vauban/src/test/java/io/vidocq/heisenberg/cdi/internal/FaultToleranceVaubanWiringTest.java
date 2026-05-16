package io.vidocq.heisenberg.cdi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import jakarta.enterprise.context.Dependent;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Test d'intégration end-to-end : un bean CDI @RequestScoped avec une méthode
 * @Retry doit voir le {@link FaultToleranceInterceptor} déclenché par Vauban,
 * et la méthode doit être exécutée jusqu'au nombre de tentatives configuré.
 *
 * <p>Reproduit le scénario qui échoue dans le TCK MicroProfile Fault Tolerance 4.1
 * ({@code RetryTest.testRetryMaxRetries}) avant le fix « class-level
 * @InterceptorBinding via BCE Enhancement ».</p>
 */
class FaultToleranceVaubanWiringTest {

    private io.vidocq.vauban.core.container.VaubanContainer container;

    @AfterEach
    void closeContainer() {
        if (container != null && container.isRunning()) {
            try { container.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    void retryAnnotatedMethodIsInterceptedAndRetried() {
        container = io.vidocq.vauban.core.container.VaubanContainer.builder()
                .addBeanClass(HeisenbergExtension.class)
                .addBeanClass(FaultToleranceInterceptor.class)
                .addBeanClass(StateRegistryBean.class)
                .addBeanClass(BulkheadStateRegistryBean.class)
                .addBeanClass(RetryBean.class)
                .build();

        container.requestContext().activate();

        var bean = container.select(RetryBean.class);
        assertNotNull(bean);
        System.err.println("[Wiring] bean class = " + bean.getClass().getName());
        try {
            var mgrField = bean.getClass().getDeclaredField("$$manager");
            mgrField.setAccessible(true);
            System.err.println("[Wiring] $$manager = " + mgrField.get(bean));
        } catch (NoSuchFieldException e) {
            System.err.println("[Wiring] no $$manager field");
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        try {
            bean.call();
        } catch (RuntimeException expected) {
            // attendu : épuisement des retries
        }

        // @Retry(maxRetries = 3) → 1 appel initial + 3 retries = 4 invocations
        assertEquals(4, RetryBean.CALLS.get(),
                "Expected 4 invocations (1 + 3 retries); got " + RetryBean.CALLS.get()
                        + ". If 1, the FaultToleranceInterceptor is not being wired by Vauban.");
    }

    @Dependent
    static class RetryBean {
        static final AtomicInteger CALLS = new AtomicInteger(0);

        @Retry(maxRetries = 3, retryOn = RuntimeException.class, delay = 0L)
        public String call() {
            CALLS.incrementAndGet();
            throw new RuntimeException("boom");
        }
    }
}



