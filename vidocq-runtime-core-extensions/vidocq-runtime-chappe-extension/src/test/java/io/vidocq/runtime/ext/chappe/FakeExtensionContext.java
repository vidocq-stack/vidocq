package io.vidocq.runtime.ext.chappe;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.config.VidocqConfig;

import java.util.Optional;

/** Stub minimal d'{@link ExtensionContext} pour les tests d'intégration. */
final class FakeExtensionContext implements ExtensionContext {

    private final VidocqConfig config;

    FakeExtensionContext(VidocqConfig config) {
        this.config = config;
    }

    @Override
    public VaubanContainer container() {
        return null;
    }

    @Override
    public VidocqConfiguration configuration() {
        return new VidocqConfiguration() {
            @Override public Optional<String> property(String key) { return config.getValue(key); }
        };
    }

    @Override
    public VidocqConfig config() {
        return config;
    }
}
