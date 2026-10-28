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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * MP FT 4.1 §4: an {@code @Asynchronous} method returning a {@link Future} completes the caller's
 * future with the nested one's outcome, and cancelling the caller's future interrupts the work.
 */
class AsynchronousEngineFutureTest {

    private static final String THREAD = "future-test";
    private static final long WAIT_SECONDS = 10;

    private static Throwable failureOf(CompletableFuture<Object> future) {
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> future.get(WAIT_SECONDS, TimeUnit.SECONDS));
        return thrown.getCause();
    }

    @Test
    void nestedFutureValueCompletesTheStage() throws Exception {
        CompletableFuture<Object> future = AsynchronousEngine
                .executeAsync(() -> CompletableFuture.completedFuture("nested"), THREAD).toCompletableFuture();

        assertEquals("nested", future.get(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void nestedFutureFailureIsUnwrapped() {
        IOException cause = new IOException("nested failure");
        CompletableFuture<Object> future = AsynchronousEngine
                .executeAsync(() -> CompletableFuture.failedFuture(cause), THREAD).toCompletableFuture();

        assertSame(cause, failureOf(future));
    }

    @Test
    void nestedExecutionExceptionWithoutCauseIsKept() {
        Future<Object> broken = new FutureTask<>(() -> null) {
            @Override
            public Object get() throws ExecutionException {
                throw new ExecutionException("no cause", null);
            }
        };
        CompletableFuture<Object> future = AsynchronousEngine.executeAsync(() -> broken, THREAD)
                .toCompletableFuture();

        Throwable failure = failureOf(future);

        assertInstanceOf(ExecutionException.class, failure);
        assertEquals("no cause", failure.getMessage());
    }

    @Test
    void nestedFutureRuntimeFailureCompletesExceptionally() {
        IllegalStateException cause = new IllegalStateException("get failed");
        Future<Object> broken = new FutureTask<>(() -> null) {
            @Override
            public Object get() {
                throw cause;
            }
        };
        CompletableFuture<Object> future = AsynchronousEngine.executeAsync(() -> broken, THREAD)
                .toCompletableFuture();

        assertSame(cause, failureOf(future));
    }

    @Test
    void interruptedWhileWaitingForTheNestedFutureCompletesExceptionally() {
        Future<Object> interrupted = new FutureTask<>(() -> null) {
            @Override
            public Object get() throws InterruptedException {
                throw new InterruptedException("waiting");
            }
        };
        CompletableFuture<Object> future = AsynchronousEngine.executeAsync(() -> interrupted, THREAD)
                .toCompletableFuture();

        assertInstanceOf(InterruptedException.class, failureOf(future));
    }

    @Test
    void cancellingWithInterruptStopsTheRunningInvocation() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CompletableFuture<Object> future = AsynchronousEngine.executeAsync(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException _) {
                interrupted.countDown();
            }
            return "done";
        }, THREAD).toCompletableFuture();
        assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS));

        assertTrue(future.cancel(true));

        assertTrue(interrupted.await(WAIT_SECONDS, TimeUnit.SECONDS), "the worker must be interrupted");
        assertTrue(future.isCancelled());
    }

    @Test
    void cancellingWithoutInterruptLeavesAStartedInvocationRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean wasInterrupted = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean invocationStarted = new AtomicBoolean(true);
        CompletableFuture<Object> future = AsynchronousEngine.executeAsync(() -> {
            started.countDown();
            try {
                release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                wasInterrupted.set(true);
            } finally {
                finished.countDown();
            }
            return "ignored";
        }, THREAD, invocationStarted).toCompletableFuture();
        assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS));

        assertTrue(future.cancel(false));
        release.countDown();

        assertTrue(finished.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertFalse(wasInterrupted.get());
        assertTrue(future.isCancelled());
    }

    @Test
    void cancellingBeforeTheInvocationStartedInterruptsIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean invocationStarted = new AtomicBoolean(false);
        CompletableFuture<Object> future = AsynchronousEngine.executeAsync(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException _) {
                interrupted.countDown();
            }
            return "queued";
        }, THREAD, invocationStarted).toCompletableFuture();
        assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS));

        assertTrue(future.cancel(false));

        assertTrue(interrupted.await(WAIT_SECONDS, TimeUnit.SECONDS), "a queued invocation is interrupted");
    }

    @Test
    void cancellingADoneFutureDoesNothing() throws Exception {
        CompletableFuture<Object> future = AsynchronousEngine.executeAsync(() -> "fast", THREAD)
                .toCompletableFuture();
        assertEquals("fast", future.get(WAIT_SECONDS, TimeUnit.SECONDS));

        assertFalse(future.cancel(true));
    }
}
