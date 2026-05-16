package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import org.eclipse.microprofile.faulttolerance.Asynchronous;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@code PolicyComposer} avec {@code @Asynchronous}.
 *
 * <p>Teste que l'ordre de composition des politiques est respecté avec @Asynchronous :
 * @Fallback → @CircuitBreaker → @Bulkhead → @Timeout → @Retry → méthode,
 * exécutées dans un virtual thread.</p>
 */
class PolicyComposerAsynchronousTest {

    @Test
    void asyncExecutesInVirtualThread() throws Exception {
        // Arrange
        final String[] capturedThreadName = new String[1];
        Method method = AsyncTestBean.class.getDeclaredMethod("asyncMethod");

        PolicyComposer.Invocation invocation = () -> {
            capturedThreadName[0] = Thread.currentThread().getName();
            return "result";
        };

        // Act
        CompletionStage<Object> result = (CompletionStage<Object>) PolicyComposer.invoke(
                invocation, new AsyncTestBean(), method, null
        );

        // Assert
        assertInstanceOf(CompletionStage.class, result);
        assertEquals("result", result.toCompletableFuture().get());
        assertTrue(capturedThreadName[0].contains("heisenberg-async"),
            "Should execute in virtual thread");
    }

    @Test
    void asyncReturnsCompletionStageImmediately() throws Exception {
        // Arrange
        Method method = AsyncTestBean.class.getDeclaredMethod("asyncMethod");
        long startTime = System.nanoTime();

        PolicyComposer.Invocation slowInvocation = () -> {
            Thread.sleep(500);  // Slow operation
            return "delayed";
        };

        // Act
        CompletionStage<Object> result = (CompletionStage<Object>) PolicyComposer.invoke(
                slowInvocation, new AsyncTestBean(), method, null
        );
        long elapsedNanos = System.nanoTime() - startTime;

        // Assert : caller should get result immediately (< 100ms)
        assertTrue(elapsedNanos < 100_000_000,
            "Caller should get CompletionStage immediately without waiting for async execution");

        // Result completes eventually
        assertEquals("delayed", result.toCompletableFuture().get());
    }

    @Test
    void asyncPropagatesException() throws Exception {
        // Arrange
        Method method = AsyncTestBean.class.getDeclaredMethod("asyncMethod");

        PolicyComposer.Invocation invocation = () -> {
            throw new IllegalArgumentException("async error");
        };

        // Act
        CompletionStage<Object> result = (CompletionStage<Object>) PolicyComposer.invoke(
                invocation, new AsyncTestBean(), method, null
        );

        // Assert
        ExecutionException ex = assertThrows(ExecutionException.class,
            () -> result.toCompletableFuture().get());
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertEquals("async error", ex.getCause().getMessage());
    }

    @Test
    void asyncWithRetryComposition() throws Exception {
        // Arrange
        Method method = AsyncRetryBean.class.getDeclaredMethod("asyncWithRetry");

        final int[] attemptCount = {0};
        PolicyComposer.Invocation invocation = () -> {
            attemptCount[0]++;
            if (attemptCount[0] < 3) {
                throw new RuntimeException("attempt-" + attemptCount[0]);
            }
            return "success-after-retries";
        };

        // Act
        CompletionStage<Object> result = (CompletionStage<Object>) PolicyComposer.invoke(
                invocation, new AsyncRetryBean(), method, null
        );

        // Assert
        assertEquals("success-after-retries", result.toCompletableFuture().get());
        assertEquals(3, attemptCount[0], "Should have attempted 3 times (initial + 2 retries)");
    }

    @Test
    void nonAsyncMethodWithoutAsync() throws Exception {
        // Arrange — no @Asynchronous annotation
        Method method = SyncTestBean.class.getDeclaredMethod("syncMethod");

        PolicyComposer.Invocation invocation = () -> "sync-result";

        // Act
        Object result = PolicyComposer.invoke(
                invocation, new SyncTestBean(), method, null
        );

        // Assert — should return directly (not wrapped in CompletionStage)
        assertEquals("sync-result", result);
        assertFalse(result instanceof CompletionStage, "Sync method should not return CompletionStage");
    }

    // Test classes with @Asynchronous annotation

    static class AsyncTestBean {
        @Asynchronous
        CompletionStage<String> asyncMethod() {
            return CompletableFuture.completedFuture("async");
        }
    }

    static class AsyncRetryBean {
        @Asynchronous
        @Retry(maxRetries = 2)
        CompletionStage<String> asyncWithRetry() {
            return CompletableFuture.completedFuture("async-retry");
        }
    }

    static class SyncTestBean {
        String syncMethod() {
            return "sync";
        }
    }
}



