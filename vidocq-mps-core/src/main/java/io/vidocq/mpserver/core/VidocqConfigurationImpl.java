package io.vidocq.mpserver.core;

import io.vidocq.mpserver.spi.VidocqConfiguration;
import io.vidocq.mpserver.spi.config.VidocqConfig;

import java.util.Optional;

/**
 * Façade {@link VidocqConfiguration} déléguant à {@link VidocqConfig}.
 * <p>
 * Conservée pour la rétrocompatibilité des extensions existantes ; les nouvelles
 * extensions devraient consommer directement {@link VidocqConfig}.
 * </p>
 */
final class VidocqConfigurationImpl implements VidocqConfiguration {

    private final VidocqConfig config;

    VidocqConfigurationImpl(VidocqConfig config) {
        this.config = config;
    }

    @Override
    public Optional<String> property(String key) {
        return config.getValue(key);
    }
}
