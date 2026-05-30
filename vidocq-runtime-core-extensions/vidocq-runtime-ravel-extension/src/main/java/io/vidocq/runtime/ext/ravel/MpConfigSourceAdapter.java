package io.vidocq.runtime.ext.ravel;

import io.vidocq.runtime.spi.config.ConfigSource;

import java.util.Set;

/**
 * Adapter exposing a {@link org.eclipse.microprofile.config.spi.ConfigSource}
 * PM in {@link ConfigSource} Vidocq.
 *
 * <p>Used by {@link RavelConfigSourceProvider} to publish the sources of
 * MP configuration (sys, env, microprofile-config.properties, user sources
 * custom, etc.) in the format expected by {@code VidocqConfig.getConfigSources()}.
 * Values ​​​​are delegated 1-for-1 to the underlying {@code ConfigSource} MP
 * — it is he who decides the resolution (priority, profiles, expressions).</p>
 *
 * <p>The delegate is kept as a reference; any hot change of a
 * MP {@code ConfigSource} (rare, but possible via {@code ConfigBuilder}) is
 * reflected immediately.</p>
 */
final class MpConfigSourceAdapter implements ConfigSource {

    private final org.eclipse.microprofile.config.spi.ConfigSource delegate;

    MpConfigSourceAdapter(org.eclipse.microprofile.config.spi.ConfigSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public int getOrdinal() {
        return delegate.getOrdinal();
    }

    @Override
    public String getValue(String key) {
        return delegate.getValue(key);
    }

    @Override
    public Set<String> getPropertyNames() {
        return delegate.getPropertyNames();
    }
}
