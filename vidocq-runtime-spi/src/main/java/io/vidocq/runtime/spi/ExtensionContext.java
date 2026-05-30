package io.vidocq.runtime.spi;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.runtime.spi.config.VidocqConfig;
import jakarta.enterprise.inject.spi.BeanManager;

/**
 * Context provided to extensions during the {@link VidocqExtension#onStart} phase.
 *
 * <p>Provides access to the CDI container and configuration.</p>
 */
public interface ExtensionContext {

    /**
     * The initialized Vauban CDI container.
     */
    VaubanContainer container();

    /**
     * The legacy Vidocq configuration.
     *
     * <p>Prefer {@link #config()} for the modern typed API.</p>
     */
    VidocqConfiguration configuration();

    /**
     * The modern Vidocq configuration.
     *
     * <p>Uses typed sources and aligns with MicroProfile Config concepts.</p>
     */
    VidocqConfig config();

    /**
     * Shortcut to the CDI {@link BeanManager}.
     */
    default BeanManager beanManager() {
        return container().getBeanManager();
    }
}
