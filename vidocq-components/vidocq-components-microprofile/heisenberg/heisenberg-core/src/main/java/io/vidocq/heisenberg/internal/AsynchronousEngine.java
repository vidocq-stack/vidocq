package io.vidocq.heisenberg.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Moteur {@code @Asynchronous} — exécution en virtual thread.
 *
 * <p>Spec MicroProfile FT 4.1 §8 : une méthode annotée {@code @Asynchronous} exécute l'invocation
 * complète (avec toutes les politiques englobantes) dans un virtual thread créé via
 * {@code Thread.ofVirtual()}. Le caller obtient immédiatement un {@code CompletionStage<T>}
 * ou {@code Future<T>} qui se complète asynchronement.</p>
 *
 * <p>L'ordre de composition reste strictement §2.5 : @Fallback → @CB → @Bulkhead → @Timeout → @Retry → méthode,
 * mais toute la chaîne s'exécute dans le virtual thread asynchrone.</p>
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
     * Exécute l'invocation dans un virtual thread et retourne un {@code CompletionStage<Object>}.
     *
     * <p>L'invocation complète (avec toutes les politiques) est exécutée dans le virtual thread.
     * Le caller reçoit immédiatement le {@code CompletionStage} qui se complète asynchronement.</p>
     *
     * @param invocation  l'invocation à exécuter (avec toutes les politiques)
     * @param threadName  base pour le nom du virtual thread (e.g., "myMethod")
     * @return un {@code CompletionStage<Object>} se complétant avec le résultat ou l'exception
     * @throws Exception jamais lancée directement — les erreurs sont propagées dans le stage
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

