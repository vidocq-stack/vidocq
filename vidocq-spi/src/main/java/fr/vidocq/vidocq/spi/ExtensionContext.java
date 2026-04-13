package fr.vidocq.vidocq.spi;

import fr.vidocq.vauban.core.container.VaubanContainer;
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
     * La configuration Vidocq.
     * <p>The Vidocq configuration.</p>
     */
    VidocqConfiguration configuration();

    /**
     * Raccourci vers le BeanManager CDI.
     * <p>Shortcut to the CDI BeanManager.</p>
     */
    default BeanManager beanManager() {
        return container().getBeanManager();
    }
}
