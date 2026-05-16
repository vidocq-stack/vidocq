package io.vidocq.heisenberg.internal;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;

/**
 * Moteur {@code @Timeout} utilisant les virtual threads (Project Loom, finalisé Java 21+).
 *
 * <p>L'invocation est forkée dans un virtual thread dédié via {@code Thread.ofVirtual()}.
 * {@code Thread.join(Duration)} attend jusqu'à la deadline ; si le thread est encore actif,
 * il est interrompu (best-effort) et une {@code TimeoutException} MP FT est levée.
 * Aucun pool de threads platform n'est créé : chaque invocation fork un virtual thread
 * éphémère. MP FT 4.1 §4.</p>
 *
 * <p><strong>Note :</strong> L'implémentation cible reste {@code StructuredTaskScope}
 * (JEP 505) qui garantit l'annulation des sous-threads à la fermeture du scope. Cette
 * implémentation sera migrée dès que l'API sortira de la période preview (Java 26+).</p>
 */
public final class TimeoutEngine {

    private TimeoutEngine() {}

    /**
     * Exécute l'invocation avec un timeout.
     *
     * @param invocation l'invocation à protéger
     * @param config     la configuration du timeout
     * @return le résultat de l'invocation
     * @throws TimeoutException si la deadline est dépassée (MP FT {@code TimeoutException})
     * @throws Exception        si l'invocation lève une exception avant la deadline
     */
    public static Object execute(PolicyComposer.Invocation invocation, TimeoutConfig config) throws Exception {
        Duration timeout = config.duration();
        AtomicReference<Object> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Thread vThread = Thread.ofVirtual()
                .name("heisenberg-timeout")
                .start(() -> {
                    try {
                        resultRef.set(invocation.proceed());
                    } catch (Throwable t) {
                        errorRef.set(t);
                    }
                });

        boolean completed = vThread.join(timeout);

        if (!completed) {
            // La deadline est dépassée : interrompre le virtual thread (best-effort).
            // Les opérations bloquantes interruptibles (Thread.sleep, I/O NIO) seront annulées.
            vThread.interrupt();
            throw new TimeoutException(
                    "Invocation timed out after " + timeout.toMillis() + " ms"
            );
        }

        // Le thread a terminé dans les temps : Thread.join() fournit happens-before.
        Throwable error = errorRef.get();
        if (error != null) {
            if (error instanceof Exception exception) {
                throw exception;
            }
            if (error instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(error);
        }

        return resultRef.get();
    }
}
