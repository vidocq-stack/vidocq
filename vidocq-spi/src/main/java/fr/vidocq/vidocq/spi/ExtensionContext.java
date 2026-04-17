package fr.vidocq.vidocq.spi;

import fr.vidocq.vauban.core.container.VaubanContainer;
import fr.vidocq.vidocq.spi.config.VidocqConfig;
import jakarta.enterprise.inject.spi.BeanManager;

/**
 * Contexte fourni aux extensions lors de la phase {@link VidocqExtension#onStart}.
 * <p>
 * Donne accès au container CDI et à la configuration.
 * </p>
 *
 * <p><b>Context provided to extensions during the {@link VidocqExtension#onStart} phase.</b>
 * Provides access to the CDI container and configuration.</p>
 */
public interface ExtensionContext {

    /**
     * Le container Vauban CDI initialisé.
     * <p>The initialized Vauban CDI container.</p>
     */
    VaubanContainer container();

    /**
     * La configuration Vidocq (API historique, en cours de remplacement par {@link #config()}).
     * <p>The legacy Vidocq configuration; prefer {@link #config()}.</p>
     */
    VidocqConfiguration configuration();

    /**
     * La configuration Vidocq moderne (sources typées, alignée MicroProfile Config).
     * <p>Modern Vidocq configuration (typed sources, MicroProfile Config aligned).</p>
     */
    VidocqConfig config();

    /**
     * Raccourci vers le BeanManager CDI.
     * <p>Shortcut to the CDI BeanManager.</p>
     */
    default BeanManager beanManager() {
        return container().getBeanManager();
    }
}
