package io.vidocq.runtime.core;

import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.config.VidocqConfig;

import java.util.Optional;

/**
 * {@link VidocqConfiguration} facade delegating to {@link VidocqConfig}.
 * <p>
 * Retained for backward compatibility of existing extensions; the news
 * extensions should directly consume {@link VidocqConfig}.
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
