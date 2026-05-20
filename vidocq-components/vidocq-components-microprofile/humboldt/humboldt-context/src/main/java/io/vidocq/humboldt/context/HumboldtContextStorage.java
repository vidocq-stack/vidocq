package io.vidocq.humboldt.context;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.Scope;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Implémentation {@link ContextStorage} d'Humboldt — backing {@link ThreadLocal}.
 *
 * <p>Conforme au contrat OpenTelemetry :</p>
 * <ul>
 *   <li>{@link #current()} retourne {@link Context#root()} si aucun contexte n'a été attaché ;</li>
 *   <li>{@link #attach(Context)} renvoie un {@link Scope} qui, à sa fermeture,
 *       restaure le contexte précédent (ou supprime l'entrée si racine) ;</li>
 *   <li>les attaches/détaches en désordre sont tolérées avec un log {@code WARNING}
 *       (alignement sur l'impl OTel de référence — pas d'exception levée).</li>
 * </ul>
 *
 * <p><strong>Virtual threads</strong> — depuis JDK 21 (JEP 444), les
 * {@link ThreadLocal} ne pinent plus la carrier thread lors d'opérations
 * bloquantes Java pures (sleep, IO NIO, etc.). Les seuls cas résiduels de
 * pinning concernent les {@code synchronized}, le code natif JNI, et certains
 * lockss legacy — aucun n'est introduit ici. Voir PLAN.md §7 pour le suivi
 * d'une éventuelle migration vers {@code ScopedValue} en M8.</p>
 */
public final class HumboldtContextStorage implements ContextStorage {

    static final HumboldtContextStorage INSTANCE = new HumboldtContextStorage();

    private static final Logger LOG = System.getLogger(HumboldtContextStorage.class.getName());
    private static final ThreadLocal<Context> THREAD_LOCAL = new ThreadLocal<>();

    private HumboldtContextStorage() {
        // singleton via ServiceLoader
    }

    @Override
    public Scope attach(Context toAttach) {
        if (toAttach == null) {
            return Scope.noop();
        }
        Context before = THREAD_LOCAL.get();
        if (toAttach == before) {
            return Scope.noop();
        }
        THREAD_LOCAL.set(toAttach);
        return new HumboldtScope(before, toAttach);
    }

    @Override
    public Context current() {
        Context c = THREAD_LOCAL.get();
        return c != null ? c : Context.root();
    }

    private static void detach(Context expected, Context previous) {
        Context actual = THREAD_LOCAL.get();
        if (actual != expected) {
            LOG.log(Level.WARNING,
                    "Context attach/detach order mismatch — attendu {0}, trouvé {1}. "
                            + "Possible cause : Scope.close() en désordre, ou contexte capturé via une lib tierce non instrumentée.",
                    expected, actual);
        }
        if (previous == null || previous == Context.root()) {
            THREAD_LOCAL.remove();
        } else {
            THREAD_LOCAL.set(previous);
        }
    }

    private static final class HumboldtScope implements Scope {

        private final Context previous;
        private final Context attached;
        private boolean closed;

        HumboldtScope(Context previous, Context attached) {
            this.previous = previous;
            this.attached = attached;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            detach(attached, previous);
        }
    }
}
