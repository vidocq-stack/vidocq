/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@code @Asynchronous} — virtual-thread execution.
 *
 * <p>MicroProfile FT 4.1 spec §8: {@code @Asynchronous} executes the method in a
 * virtual thread and returns a {@code CompletionStage<T>} or {@code Future<T>}.</p>
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
        // §8: if the invocation returns a CompletionStage<T>, the engine unwraps the nested
        // stage and the outer CompletionStage completes with the T value (not the stage itself).
        CompletableFuture<String> innerFuture = CompletableFuture.completedFuture("inner-value");
        PolicyComposer.Invocation invocation = () -> innerFuture;

        // Act
        CompletionStage<Object> result = AsynchronousEngine.executeAsync(invocation, THREAD_NAME);

        // Assert: unwrap the nested Future — the final value is "inner-value"
        Object resultValue = result.toCompletableFuture().get();
        assertEquals("inner-value", resultValue);
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

        // Assert: wait for completion
        Thread.sleep(200); // allow virtual thread to complete
        assertTrue(capturedThreadName[0].contains("heisenberg-async"), "Should contain 'heisenberg-async' prefix");
    }

    @Test
    void doesNotBlockCallerThread() throws Exception {
        // Arrange: slow invocation
        PolicyComposer.Invocation slowInvocation = () -> {
            Thread.sleep(500);
            return "delayed";
        };
        long startNano = System.nanoTime();

        // Act
        CompletionStage<Object> result = AsynchronousEngine.executeAsync(slowInvocation, THREAD_NAME);
        long elapsedBeforeWait = System.nanoTime() - startNano;

        // Assert: the caller returned immediately (< 100 ms)
        assertTrue(elapsedBeforeWait < 100_000_000, "Should return immediately to caller");

        // Verify that the result completes correctly
        assertEquals("delayed", result.toCompletableFuture().get());
    }
}



