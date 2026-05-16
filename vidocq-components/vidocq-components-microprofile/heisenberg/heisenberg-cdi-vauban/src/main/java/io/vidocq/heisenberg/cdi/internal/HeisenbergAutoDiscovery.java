package io.vidocq.heisenberg.cdi.internal;

import java.util.ServiceLoader;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;

/**
 * Pont ServiceLoader vers le resolver MP Config réellement présent sur le classpath (Ravel).
 */
public final class HeisenbergAutoDiscovery extends ConfigProviderResolver {

    private ConfigProviderResolver delegate;

    // Constructeur public par défaut requis par ServiceLoader
    public HeisenbergAutoDiscovery() {
        // Lazy initialization pour éviter les appels circulaires ServiceLoader
    }

    @Override
    public Config getConfig() {
        return getDelegate().getConfig();
    }

    @Override
    public Config getConfig(ClassLoader classLoader) {
        return getDelegate().getConfig(classLoader);
    }

    @Override
    public ConfigBuilder getBuilder() {
        return getDelegate().getBuilder();
    }

    @Override
    public void registerConfig(Config config, ClassLoader classLoader) {
        getDelegate().registerConfig(config, classLoader);
    }

    @Override
    public void releaseConfig(Config config) {
        getDelegate().releaseConfig(config);
    }

    private ConfigProviderResolver getDelegate() {
        if (delegate == null) {
            delegate = discoverDelegate();
        }
        return delegate;
    }

    private ConfigProviderResolver discoverDelegate() {
        ServiceLoader<ConfigProviderResolver> loaders = ServiceLoader.load(ConfigProviderResolver.class);
        for (ConfigProviderResolver resolver : loaders) {
            if (!resolver.getClass().equals(HeisenbergAutoDiscovery.class)) {
                return resolver;
            }
        }
        throw new IllegalStateException("No MP ConfigProviderResolver found besides HeisenbergAutoDiscovery");
    }
}
