package io.vidocq.runtime.spi;

import io.vidocq.vauban.core.container.VaubanContainerBuilder;

/**
 * Point d'extension principal de Vidocq.
 * <p>
 * Les extensions sont découvertes via {@link java.util.ServiceLoader} et
 * exécutées selon leur {@link #priority() priorité} durant le cycle de vie
 * du serveur :
 * <ol>
 *   <li>{@link #configure} — configuration avant le boot CDI</li>
 *   <li>{@link #beforeStart} — enrichissement du container builder</li>
 *   <li>{@link #onStart} — le container CDI est prêt</li>
 *   <li>{@link #onStop} — arrêt du serveur (ordre inverse)</li>
 * </ol>
 *
 * <p><b>Main extension point for Vidocq.</b>
 * Extensions are discovered via {@link java.util.ServiceLoader} and
 * executed by {@link #priority()} during the server lifecycle.</p>
 */
public interface VidocqExtension {

    /**
     * Nom unique de l'extension. / Unique extension name.
     */
    String name();

    /**
     * Priorité d'exécution (plus bas = plus prioritaire, défaut 1000).
     * <p>Execution priority (lower = higher priority, default 1000).</p>
     */
    default int priority() {
        return 1000;
    }

    /**
     * Phase de configuration : appelée avant le boot CDI.
     * <p>Configuration phase: called before CDI boot.</p>
     */
    default void configure(VidocqConfiguration config) {}

    /**
     * Phase de pré-démarrage : enrichir le {@link VaubanContainerBuilder}.
     * <p>Pre-start phase: enrich the {@link VaubanContainerBuilder}.</p>
     */
    default void beforeStart(VaubanContainerBuilder builder) {}

    /**
     * Phase de démarrage : le container CDI est initialisé.
     * <p>Start phase: the CDI container is initialized.</p>
     */
    default void onStart(ExtensionContext context) {}

    /**
     * Phase d'arrêt : libérer les ressources.
     * <p>Stop phase: release resources.</p>
     */
    default void onStop() {}
}
