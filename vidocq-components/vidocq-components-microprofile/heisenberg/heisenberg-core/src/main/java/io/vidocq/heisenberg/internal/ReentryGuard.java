package io.vidocq.heisenberg.internal;

/**
 * Indicateur "ré-entrée dans la chaîne Fault Tolerance" porté par {@link ScopedValue}.
 *
 * <p>Quand un intercepteur CDI Vauban appelle {@code context.proceed()} sur une méthode
 * {@code $$Intercepted}, le container ré-applique toute la chaîne d'intercepteurs au
 * lieu d'invoquer directement la méthode cible. Sans détection de ré-entrée, chaque
 * appel cascade et ré-applique toutes les politiques Fault Tolerance, créant une boucle
 * exponentielle.</p>
 *
 * <p>L'intercepteur lie {@code ACTIVE = TRUE} avant d'entrer dans {@link PolicyComposer},
 * et toute ré-entrée détectée bypass la chaîne pour appeler directement la méthode.</p>
 *
 * <p><strong>Propagation à travers les virtual threads</strong> : {@code ScopedValue}
 * n'est <em>pas</em> hérité par les threads créés via {@code Thread.ofVirtual().start()}.
 * {@link AsynchronousEngine} capture donc la valeur active et la re-binde explicitement
 * dans le virtual thread asynchrone.</p>
 */
public final class ReentryGuard {

    /** Liaison ScopedValue indiquant qu'on est déjà dans la chaîne FT pour ce thread. */
    public static final ScopedValue<Boolean> ACTIVE = ScopedValue.newInstance();

    private ReentryGuard() {}

    /** Retourne {@code true} si la chaîne FT est déjà active sur le thread courant. */
    public static boolean isActive() {
        return ACTIVE.isBound() && Boolean.TRUE.equals(ACTIVE.get());
    }
}

