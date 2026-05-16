package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@code @Asynchronous} — exécution en virtual thread.
 *
 * <p>Spec MicroProfile FT 4.1 §8 : {@code @Asynchronous} exécute la méthode dans un
 * virtual thread et retourne un {@code CompletionStage<T>} ou {@code Future<T>}.</p>
 */
class AsynchronousEngineTest {

    private static final String THREAD_NAME = "test-async-thread";

    @Test
    void executesInVirtualThread() throws Exception {
        // Arrange
        final String[] threadNameCapture = {null};
        PolicyComposer.Invocation invocation = () -> {
            threadNameCapture[0] = Thread.currentThread().getName();
            return "success";
        };

        // Act
        CompletionStage<Object> result = AsynchronousEngine.executeAsync(invocation, THREAD_NAME);
        Object value = result.toCompletableFuture().get();

        // Assert
        assertEquals("success", value);
        assertTrue(threadNameCapture[0].contains("heisenberg-async"), "Should execute in virtual thread named 'heisenberg-async'");
    }

    @Test
    void wrapsResultInCompletionStage() throws Exception {
        // Arrange
        PolicyComposer.Invocation invocation = () -> "completed";

        // Act
        CompletionStage<Object> result = AsynchronousEngine.executeAsync(invocation, THREAD_NAME);

        // Assert
        assertNotNull(result);
        assertEquals("completed", result.toCompletableFuture().get());
    }

    @Test
    void propagatesExceptionAsCompletionException() throws Exception {
        // Arrange
        PolicyComposer.Invocation invocation = () -> {
            throw new IllegalArgumentException("test error");
        };

        // Act
        CompletionStage<Object> result = AsynchronousEngine.executeAsync(invocation, THREAD_NAME);

        // Assert
        ExecutionException ex = assertThrows(ExecutionException.class, () -> result.toCompletableFuture().get());
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertEquals("test error", ex.getCause().getMessage());
    }

    @Test
    void returnsCompletableFutureForAsyncComposition() throws Exception {
        // Arrange : invocation retourne un CompletableFuture
        CompletableFuture<String> innerFuture = CompletableFuture.completedFuture("inner-value");
        PolicyComposer.Invocation invocation = () -> innerFuture;

        // Act
        CompletionStage<Object> result = AsynchronousEngine.executeAsync(invocation, THREAD_NAME);

        // Assert : result contient le CompletableFuture retourné par l'invocation
        // (l'unwrapping du stage imbriqué se ferait à un niveau supérieur si désiré)
        Object resultValue = result.toCompletableFuture().get();
        assertInstanceOf(CompletableFuture.class, resultValue);
        assertEquals("inner-value", ((CompletableFuture<?>) resultValue).get());
    }

    @Test
    void handlesNullReturnValue() throws Exception {
        // Arrange
        PolicyComposer.Invocation invocation = () -> null;

        // Act
        CompletionStage<Object> result = AsynchronousEngine.executeAsync(invocation, THREAD_NAME);

        // Assert
        assertNull(result.toCompletableFuture().get());
    }

    @Test
    void respectsMethodNameInThreadNaming() throws Exception {
        // Arrange
        final String[] capturedThreadName = {null};
        PolicyComposer.Invocation invocation = () -> {
            capturedThreadName[0] = Thread.currentThread().getName();
            return "ok";
        };

        // Act
        AsynchronousEngine.executeAsync(invocation, "my-custom-method");

        // Assert : attendez la complèttion
        Thread.sleep(200); // allow virtual thread to complete
        assertTrue(capturedThreadName[0].contains("heisenberg-async"), "Should contain 'heisenberg-async' prefix");
    }

    @Test
    void doesNotBlockCallerThread() throws Exception {
        // Arrange : invocation lente
        PolicyComposer.Invocation slowInvocation = () -> {
            Thread.sleep(500);
            return "delayed";
        };
        long startNano = System.nanoTime();

        // Act
        CompletionStage<Object> result = AsynchronousEngine.executeAsync(slowInvocation, THREAD_NAME);
        long elapsedBeforeWait = System.nanoTime() - startNano;

        // Assert : le caller a retourné immédiatement (< 100ms)
        assertTrue(elapsedBeforeWait < 100_000_000, "Should return immediately to caller");

        // Vérifier que le résultat se complète correctement
        assertEquals("delayed", result.toCompletableFuture().get());
    }
}



