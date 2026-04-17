package fr.vidocq.vidocq.core;

import fr.vidocq.vauban.core.container.VaubanContainer;
import fr.vidocq.vidocq.spi.ExtensionContext;
import fr.vidocq.vidocq.spi.VidocqConfiguration;
import fr.vidocq.vidocq.spi.config.VidocqConfig;

/**
 * Implémentation du contexte d'extension fourni lors du {@code onStart}.
 */
record ExtensionContextImpl(
        VaubanContainer container,
        VidocqConfiguration configuration,
        VidocqConfig config
) implements ExtensionContext {}
