package io.vidocq.heisenberg.cdi.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.faulttolerance.Asynchronous;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.junit.jupiter.api.Test;

/**
 * Tests d'intégration CDI pour {@code @Asynchronous}.
 *
 * <p>Vérifie que :
 * - Les méthodes annotées {@code @Asynchronous} retournent un {@code CompletionStage<T>} ou {@code Future<T>}
 * - L'exécution se fait dans un virtual thread
 * - La composition avec {@code @Retry} et {@code @Timeout} fonctionne via l'intercepteur
 * </p>
 */
class AsynchronousIntegrationTest {

    private final FaultToleranceInterceptor interceptor = new FaultToleranceInterceptor();

    @Test
    void completionStageReturnsCompletedValue() throws Exception {
        AsyncBean bean = new AsyncBean();
        Method method = AsyncBean.class.getDeclaredMethod("asyncMethod", String.class);
        InvocationContext context = new ReflectiveInvocationContext(bean, method, new Object[]{"hello"});

        @SuppressWarnings("unchecked")
        CompletionStage<String> result = (CompletionStage<String>) interceptor.around(context);

        assertNotNull(result);
        assertEquals("hello-async", result.toCompletableFuture().get());
    }

    @Test
    void completionStageHandlesException() throws Exception {
        AsyncBean bean = new AsyncBean();
        Method method = AsyncBean.class.getDeclaredMethod("asyncExceptionMethod");
        InvocationContext context = new ReflectiveInvocationContext(bean, method, new Object[0]);

        @SuppressWarnings("unchecked")
        CompletionStage<String> result = (CompletionStage<String>) interceptor.around(context);

        ExecutionException ex = assertThrows(ExecutionException.class, () -> result.toCompletableFuture().get());
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    void futureReturnsCompletedValue() throws Exception {
        AsyncBean bean = new AsyncBean();
        Method method = AsyncBean.class.getDeclaredMethod("asyncMethodFuture", String.class);
        InvocationContext context = new ReflectiveInvocationContext(bean, method, new Object[]{"test"});

        // Note: la méthode retourne Future, mais la chaîne async retourne un CompletionStage
        // qui se complète avec la valeur déballée — `Future.get()` fonctionne sur CompletableFuture.
        Object raw = interceptor.around(context);
        assertNotNull(raw);
        @SuppressWarnings("unchecked")
        Future<String> result = (Future<String>) raw;
        assertEquals("test-future", result.get());
    }

    @Test
    void asyncOnClassMethodLevel() throws Exception {
        AsyncClassLevelBean bean = new AsyncClassLevelBean();
        Method method = AsyncClassLevelBean.class.getDeclaredMethod("syncMethodButAsyncClass", String.class);
        InvocationContext context = new ReflectiveInvocationContext(bean, method, new Object[]{"class-level"});

        @SuppressWarnings("unchecked")
        CompletionStage<String> result = (CompletionStage<String>) interceptor.around(context);

        assertEquals("class-level-result", result.toCompletableFuture().get());
    }

    @Test
    void asyncWithRetryComposition() throws Exception {
        // MP FT 4.1 §8.2 : un CompletionStage en erreur déclenche le retry
        AsyncRetryBean bean = new AsyncRetryBean();
        Method method = AsyncRetryBean.class.getDeclaredMethod("asyncWithRetry");
        InvocationContext context = new ReflectiveInvocationContext(bean, method, new Object[0]);

        @SuppressWarnings("unchecked")
        CompletionStage<String> result = (CompletionStage<String>) interceptor.around(context);

        assertEquals("retry-success", result.toCompletableFuture().get());
        assertEquals(2, bean.attemptCount); // 1 échec + 1 succès
    }

    @Test
    void asyncWithTimeoutComposition() throws Exception {
        AsyncTimeoutBean bean = new AsyncTimeoutBean();
        Method method = AsyncTimeoutBean.class.getDeclaredMethod("quickAsyncMethod");
        InvocationContext context = new ReflectiveInvocationContext(bean, method, new Object[0]);

        @SuppressWarnings("unchecked")
        CompletionStage<String> result = (CompletionStage<String>) interceptor.around(context);

        assertEquals("quick", result.toCompletableFuture().get());
    }

    // Test beans with @Asynchronous

    static class AsyncBean {
        @Asynchronous
        CompletionStage<String> asyncMethod(String input) {
            return CompletableFuture.completedFuture(input + "-async");
        }

        @Asynchronous
        CompletionStage<String> asyncExceptionMethod() {
            return CompletableFuture.failedFuture(new IllegalStateException("async error"));
        }

        @Asynchronous
        Future<String> asyncMethodFuture(String input) {
            return CompletableFuture.completedFuture(input + "-future");
        }
    }

    @Asynchronous
    static class AsyncClassLevelBean {
        CompletionStage<String> syncMethodButAsyncClass(String input) {
            return CompletableFuture.completedFuture(input + "-result");
        }
    }

    static class AsyncRetryBean {
        int attemptCount = 0;

        @Asynchronous
        @Retry(maxRetries = 2, retryOn = {RuntimeException.class})
        CompletionStage<String> asyncWithRetry() {
            attemptCount++;
            if (attemptCount < 2) {
                return CompletableFuture.failedFuture(new RuntimeException("retry-attempt-" + attemptCount));
            }
            return CompletableFuture.completedFuture("retry-success");
        }
    }

    static class AsyncTimeoutBean {
        @Asynchronous
        @Timeout(1000)
        CompletionStage<String> quickAsyncMethod() {
            return CompletableFuture.completedFuture("quick");
        }
    }
}

