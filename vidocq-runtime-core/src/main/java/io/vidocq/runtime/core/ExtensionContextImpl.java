package io.vidocq.runtime.core;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.config.VidocqConfig;

/**
 * Implementation of the extension context provided during {@code onStart}.
 */
record ExtensionContextImpl(
        VaubanContainer container,
        VidocqConfiguration configuration,
        VidocqConfig config
) implements ExtensionContext {}
