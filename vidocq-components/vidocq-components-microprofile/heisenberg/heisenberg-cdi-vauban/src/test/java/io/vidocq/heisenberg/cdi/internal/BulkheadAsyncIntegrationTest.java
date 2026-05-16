package io.vidocq.heisenberg.cdi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import io.vidocq.heisenberg.internal.BulkheadStateRegistry;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.faulttolerance.Asynchronous;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException;
import org.junit.jupiter.api.Test;

/**
 * Tests d'intégration {@code @Asynchronous @Bulkhead} — MP FT 4.1 §7.2 + §8.
 *
 * <p>Vérifie que le bulkhead async :
 * <ul>
 *   <li>Laisse passer la fast-path quand des permits sont libres</li>
 *   <li>Met en file d'attente lorsqu'il n'y a pas de permit, puis débloque dès libération</li>
 *   <li>Renvoie un {@code CompletionStage} en erreur ({@code BulkheadException}) quand la
 *       file d'attente est pleine, conformément à §8.2</li>
 *   <li>Compose avec {@code @Fallback} pour récupérer sur saturation</li>
 * </ul>
 */
class BulkheadAsyncIntegrationTest {

    private final BulkheadStateRegistryBean bulkheadRegistry = new BulkheadStateRegistryBean();
    private final FaultToleranceInterceptor interceptor = new FaultToleranceInterceptor();

    BulkheadAsyncIntegrationTest() {
        try {
            var stateField = FaultToleranceInterceptor.class.getDeclaredField("stateRegistry");
            stateField.setAccessible(true);
            stateField.set(interceptor, new StateRegistryBean());

            var bulkheadField = FaultToleranceInterceptor.class.getDeclaredField("bulkheadRegistry");
            bulkheadField.setAccessible(true);
            bulkheadField.set(interceptor, bulkheadRegistry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void asyncBulkheadFastPathSucceeds() throws Exception {
        AsyncBulkheadService bean = new AsyncBulkheadService();
        Method method = AsyncBulkheadService.class.getDeclaredMethod("guarded");
        InvocationContext context = new ReflectiveInvocationContext(bean, method, new Object[0]);

        @SuppressWarnings("unchecked")
        CompletionStage<String> result = (CompletionStage<String>) interceptor.around(context);
        assertEquals("async-ok", result.toCompletableFuture().get(2, TimeUnit.SECONDS));
    }

    @Test
    void asyncBulkheadWrapsBulkheadExceptionInStageWhenQueueFull() throws Exception {
        AsyncBulkheadService bean = new AsyncBulkheadService();
        Method method = AsyncBulkheadService.class.getDeclaredMethod("guarded");

        // saturate both permits and waitingQueue using canonical keys
        BulkheadStateRegistry.BulkheadState state = bulkheadRegistry.getAsyncState(
                StateKeys.bean(AsyncBulkheadService.class), StateKeys.method(method), 1, 1);
        state.permits().acquire();
        state.waitingQueue().acquire();

        try {
            InvocationContext context = new ReflectiveInvocationContext(bean, method, new Object[0]);

            @SuppressWarnings("unchecked")
            CompletionStage<String> result = (CompletionStage<String>) interceptor.around(context);

            // §8.2 : l'exception est portée par le stage
            ExecutionException ee = assertThrows(ExecutionException.class,
                    () -> result.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertInstanceOf(BulkheadException.class, ee.getCause());
        } finally {
            state.waitingQueue().release();
            state.permits().release();
        }
    }

    @Test
    void asyncBulkheadFallbackOnSaturation() throws Exception {
        AsyncBulkheadFallbackService bean = new AsyncBulkheadFallbackService();
        Method method = AsyncBulkheadFallbackService.class.getDeclaredMethod("guarded");

        BulkheadStateRegistry.BulkheadState state = bulkheadRegistry.getAsyncState(
                StateKeys.bean(AsyncBulkheadFallbackService.class), StateKeys.method(method), 1, 1);
        state.permits().acquire();
        state.waitingQueue().acquire();

        try {
            InvocationContext context = new ReflectiveInvocationContext(bean, method, new Object[0]);

            @SuppressWarnings("unchecked")
            CompletionStage<String> result = (CompletionStage<String>) interceptor.around(context);
            assertEquals("fallback-async", result.toCompletableFuture().get(2, TimeUnit.SECONDS));
        } finally {
            state.waitingQueue().release();
            state.permits().release();
        }
    }

    @Test
    void asyncBulkheadQueuesUntilPermitFreed() throws Exception {
        AsyncBulkheadGatedService bean = new AsyncBulkheadGatedService();
        Method method = AsyncBulkheadGatedService.class.getDeclaredMethod("guarded");

        // First call: takes the only permit (will block on internal gate)
        InvocationContext ctx1 = new ReflectiveInvocationContext(bean, method, new Object[0]);
        @SuppressWarnings("unchecked")
        CompletionStage<String> first = (CompletionStage<String>) interceptor.around(ctx1);

        // Attendre que le premier ait pris le permit
        assertTrue(bean.entered.tryAcquire(2, TimeUnit.SECONDS));

        // Second call: should be queued (permit pris, place dans la queue)
        InvocationContext ctx2 = new ReflectiveInvocationContext(bean, method, new Object[0]);
        @SuppressWarnings("unchecked")
        CompletionStage<String> second = (CompletionStage<String>) interceptor.around(ctx2);

        // Libérer le premier : le second doit pouvoir progresser
        bean.gate.release();
        bean.gate.release();

        assertEquals("done-1", first.toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertEquals("done-2", second.toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertEquals(2, bean.invocations.get());
    }

    // ---- Services under test ----

    static class AsyncBulkheadService {
        @Asynchronous
        @Bulkhead(value = 1, waitingTaskQueue = 1)
        CompletionStage<String> guarded() {
            return CompletableFuture.completedFuture("async-ok");
        }
    }

    static class AsyncBulkheadFallbackService {
        @Asynchronous
        @Bulkhead(value = 1, waitingTaskQueue = 1)
        @Fallback(fallbackMethod = "recover")
        CompletionStage<String> guarded() {
            return CompletableFuture.completedFuture("main-async");
        }

        @SuppressWarnings("unused")
        CompletionStage<String> recover() {
            return CompletableFuture.completedFuture("fallback-async");
        }
    }

    /** Bean qui se bloque sur une grille jusqu'à libération externe. */
    static class AsyncBulkheadGatedService {
        final Semaphore gate = new Semaphore(0);
        final Semaphore entered = new Semaphore(0);
        final AtomicInteger invocations = new AtomicInteger();

        @Asynchronous
        @Bulkhead(value = 1, waitingTaskQueue = 2)
        CompletionStage<String> guarded() {
            int n = invocations.incrementAndGet();
            entered.release();
            try {
                gate.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return CompletableFuture.failedFuture(ie);
            }
            return CompletableFuture.completedFuture("done-" + n);
        }
    }
}


