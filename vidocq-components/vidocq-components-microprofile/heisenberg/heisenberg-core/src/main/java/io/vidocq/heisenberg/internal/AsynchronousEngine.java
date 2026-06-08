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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code @Asynchronous} engine — virtual-thread execution.
 *
 * <p>MicroProfile FT 4.1 spec §8: a method annotated with {@code @Asynchronous} executes the
 * complete invocation (with all surrounding policies) in a virtual thread created via
 * {@code Thread.ofVirtual()}. The caller immediately gets a {@code CompletionStage<T>}
 * or {@code Future<T>} that completes asynchronously.</p>
 *
 * <p>The composition order remains strictly §2.5: @Fallback → @CB → @Bulkhead → @Timeout → @Retry → method,
 * but the whole chain executes in the asynchronous virtual thread.</p>
 */
public final class AsynchronousEngine {

    private AsynchronousEngine() {}

    public static CompletionStage<Object> executeAsync(
            PolicyComposer.Invocation invocation,
            String threadName
    ) throws Exception {
        return executeAsync(invocation, threadName, null);
    }

    /**
     * Executes the invocation in a virtual thread and returns a {@code CompletionStage<Object>}.
     *
     * <p>The complete invocation (with all policies) is executed in the virtual thread.
     * The caller immediately receives the {@code CompletionStage}, which completes asynchronously.</p>
     *
     * @param invocation  the invocation to execute (with all policies)
     * @param threadName  base for the virtual-thread name (e.g., "myMethod")
     * @return a {@code CompletionStage<Object>} completing with the result or exception
     * @throws Exception never thrown directly — errors are propagated in the stage
     */
    public static CompletionStage<Object> executeAsync(
            PolicyComposer.Invocation invocation,
            String threadName,
            java.util.concurrent.atomic.AtomicBoolean invocationStarted
    ) throws Exception {
        AtomicReference<Thread> workerRef = new AtomicReference<>();
        CompletableFuture<Object> future = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                boolean shouldInterrupt = mayInterruptIfRunning
                        || (invocationStarted != null && !invocationStarted.get());
                if (cancelled && shouldInterrupt) {
                    Thread worker = workerRef.get();
                    if (worker != null) {
                        worker.interrupt();
                    }
                }
                return cancelled;
            }
        };

        Thread worker = Thread.ofVirtual()
                .name("heisenberg-async-" + threadName)
                .start(() -> {
                    try {
                        Object result = invocation.proceed();
                        if (result instanceof Future<?> nestedFuture) {
                            completeFromFuture(future, nestedFuture);
                            return;
                        }
                        if (!future.isDone()) {
                            future.complete(result);
                        }
                    } catch (Throwable e) {
                        if (!future.isDone()) {
                            future.completeExceptionally(e);
                        }
                    }
                });
        workerRef.set(worker);
        if (future.isCancelled()) {
            worker.interrupt();
        }

        return future;
    }

    private static void completeFromFuture(CompletableFuture<Object> outer, Future<?> nested) {
        try {
            Object value = nested.get();
            if (!outer.isDone()) {
                outer.complete(value);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (!outer.isDone()) {
                outer.completeExceptionally(interrupted);
            }
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause() == null ? execution : execution.getCause();
            if (!outer.isDone()) {
                outer.completeExceptionally(cause);
            }
        } catch (Throwable failure) {
            if (!outer.isDone()) {
                outer.completeExceptionally(failure);
            }
        }
    }
}

