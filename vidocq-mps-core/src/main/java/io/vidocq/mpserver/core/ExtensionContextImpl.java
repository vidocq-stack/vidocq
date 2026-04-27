package io.vidocq.mpserver.core;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.mpserver.spi.ExtensionContext;
import io.vidocq.mpserver.spi.VidocqConfiguration;
import io.vidocq.mpserver.spi.config.VidocqConfig;

/**
 * Implémentation du contexte d'extension fourni lors du {@code onStart}.
 */
record ExtensionContextImpl(
        VaubanContainer container,
        VidocqConfiguration configuration,
        VidocqConfig config
) implements ExtensionContext {}
