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
        return execute(invocation, config, false);
    }

    /**
     * Exécute l'invocation avec un timeout.
     *
     * @param invocation     l'invocation à protéger
     * @param config         la configuration du timeout
     * @param asyncCall      {@code true} si appelé depuis le wrapper async (le caller
     *                       a déjà rendu la main, on ne doit pas le bloquer au-delà du
     *                       deadline). {@code false} = sync : §4.1.2 impose d'attendre
     *                       la fin réelle de la méthode (uninterruptable) avant de lever
     *                       la {@link TimeoutException}.
     */
    public static Object execute(PolicyComposer.Invocation invocation, TimeoutConfig config, boolean asyncCall) throws Exception {
        Duration timeout = config.duration();
        AtomicReference<Object> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // ScopedValue (ReentryGuard) n'est pas hérité par les vthreads créés via .start().
        // Sans ré-injection, invocation.proceed() ré-entre dans le proxy CDI puis dans
        // FaultToleranceInterceptor, qui re-déroule toute la chaîne et explose en récursion.
        final boolean reentryActive = ReentryGuard.isActive();

        Thread vThread = Thread.ofVirtual()
                .name("heisenberg-timeout")
                .start(() -> {
                    Runnable body = () -> {
                        try {
                            resultRef.set(invocation.proceed());
                        } catch (Throwable t) {
                            errorRef.set(t);
                        }
                    };
                    if (reentryActive) {
                        ScopedValue.where(ReentryGuard.ACTIVE, Boolean.TRUE).run(body);
                    } else {
                        body.run();
                    }
                });

        boolean completed = vThread.join(timeout);

        if (!completed) {
            // La deadline est dépassée : interrompre le virtual thread (best-effort).
            // Les opérations bloquantes interruptibles (Thread.sleep, I/O NIO) seront annulées.
            vThread.interrupt();

            // §4.1.2 (mode sync uniquement) : on attend la fin effective de la méthode avant
            // de propager TimeoutException. Pour les méthodes uninterruptable, le caller reste
            // bloqué jusqu'au retour réel. En mode async, on rend la main immédiatement (le
            // virtual thread asynchrone porte déjà l'attente).
            if (!asyncCall) {
                vThread.join();
            }

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
