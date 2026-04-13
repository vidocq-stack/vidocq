package fr.vidocq.vidocq.core;

import fr.vidocq.vauban.core.container.VaubanContainer;
import fr.vidocq.vidocq.spi.ExtensionContext;
import fr.vidocq.vidocq.spi.VidocqConfiguration;

/**
 * Implémentation du contexte d'extension fourni lors du {@code onStart}.
 */
record ExtensionContextImpl(
        VaubanContainer container,
        VidocqConfiguration configuration
) implements ExtensionContext {}
