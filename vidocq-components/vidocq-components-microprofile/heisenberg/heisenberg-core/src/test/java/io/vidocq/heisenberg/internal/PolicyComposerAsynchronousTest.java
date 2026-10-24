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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.faulttolerance.Asynchronous;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@code PolicyComposer} with {@code @Asynchronous}.
 *
 * <p>Tests that the policy-composition order is respected with @Asynchronous:
 * @Fallback → @CircuitBreaker → @Bulkhead → @Timeout → @Retry → method,
 * executed in a virtual thread.</p>
 */
class PolicyComposerAsynchronousTest {

    /** Upper bound on how long the blocked invocation waits for the test to release it. */
    private static final long RELEASE_WAIT_SECONDS = 10;

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
        // Arrange — the invocation cannot finish before the test releases it, so a caller that
        // waited for it would only get a stage back once the release wait has run out.
        Method method = AsyncTestBean.class.getDeclaredMethod("asyncMethod");
        CountDownLatch release = new CountDownLatch(1);

        PolicyComposer.Invocation blockedInvocation = () -> {
            if (!release.await(RELEASE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Invocation was never released by the test");
            }
            return "delayed";
        };

        // Act
        CompletionStage<Object> result = (CompletionStage<Object>) PolicyComposer.invoke(
                blockedInvocation, new AsyncTestBean(), method, null
        );

        // Assert : the caller holds the stage while the invocation is still running
        assertFalse(result.toCompletableFuture().isDone(),
            "Caller should get CompletionStage immediately without waiting for async execution");

        // Result completes once the invocation is released
        release.countDown();
        assertEquals("delayed", result.toCompletableFuture().get(RELEASE_WAIT_SECONDS, TimeUnit.SECONDS));
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



