package io.vidocq.heisenberg.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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
            String threadName
    ) throws Exception {
        CompletableFuture<Object> future = new CompletableFuture<>();

        Thread.ofVirtual()
                .name("heisenberg-async-" + threadName)
                .start(() -> {
                    try {
                        Object result = invocation.proceed();
                        future.complete(result);
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });

        return future;
    }
}

