package io.vidocq.runtime.ext.ravel;

import io.vidocq.runtime.spi.config.ConfigSource;

import java.util.Set;

/**
 * Adapter exposant une {@link org.eclipse.microprofile.config.spi.ConfigSource}
 * MP en {@link ConfigSource} Vidocq.
 *
 * <p>Utilisé par {@link RavelConfigSourceProvider} pour publier les sources de
 * configuration MP (sys, env, microprofile-config.properties, sources user
 * custom, etc.) au format attendu par {@code VidocqConfig.getConfigSources()}.
 * Les valeurs sont déléguées 1-pour-1 au {@code ConfigSource} MP sous-jacent
 * — c'est lui qui décide de la résolution (priorité, profils, expressions).</p>
 *
 * <p>Le delegate est conservé en référence ; tout changement à chaud d'une
 * MP {@code ConfigSource} (rare, mais possible via {@code ConfigBuilder}) est
 * reflété immédiatement.</p>
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
