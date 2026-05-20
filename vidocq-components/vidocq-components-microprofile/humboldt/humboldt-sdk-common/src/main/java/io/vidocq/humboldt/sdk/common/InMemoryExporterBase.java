package io.vidocq.humboldt.sdk.common;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Squelette d'exporter in-memory mutualisé pour les 3 signaux (spans, metrics, logs).
 *
 * <p>Les sous-classes concrètes implémentent l'interface SDK spécifique
 * ({@code SpanExporter}, {@code MetricExporter}, {@code LogRecordExporter})
 * et délèguent {@code export(...)} à {@link #addAll(Collection)}.</p>
 *
 * <p>Volontairement, {@link #shutdown()} ne purge PAS {@link #collected} —
 * les tests qui inspectent l'exporter via try-with-resources sur le provider
 * peuvent ainsi lire les données drainées après {@code close()}. Utiliser
 * {@link #reset()} pour vider explicitement.</p>
 */
public abstract class InMemoryExporterBase<T> {

    private final List<T> collected = new CopyOnWriteArrayList<>();
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * @return un snapshot immutable des éléments collectés depuis le démarrage
     *         (ou depuis le dernier {@link #reset()}).
     */
    public final List<T> getCollected() {
        return List.copyOf(collected);
    }

    /** Vide la liste — n'affecte pas l'état {@code stopped}. */
    public final void reset() {
        collected.clear();
    }

    /**
     * À appeler depuis {@code export(...)} des sous-classes.
     *
     * @return {@link CompletableResultCode#ofFailure()} si déjà shutdown,
     *         sinon {@link CompletableResultCode#ofSuccess()}.
     */
    protected final CompletableResultCode addAll(Collection<T> items) {
        if (stopped.get()) return CompletableResultCode.ofFailure();
        collected.addAll(items);
        return CompletableResultCode.ofSuccess();
    }

    public final CompletableResultCode flushBase() {
        return CompletableResultCode.ofSuccess();
    }

    public final CompletableResultCode shutdownBase() {
        stopped.set(true);
        return CompletableResultCode.ofSuccess();
    }
}
