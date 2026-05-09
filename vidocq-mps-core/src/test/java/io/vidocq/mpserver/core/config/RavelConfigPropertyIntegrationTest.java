package io.vidocq.mpserver.core.config;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vérifie que Ravel (@ConfigProperty) fonctionne dans le contexte Vauban
 * après son intégration dans vidocq-mps-core.
 *
 * <p>M6 — swap Smallrye Config → Ravel dans la plateforme Vidocq MPS.</p>
 */
@DisplayName("Ravel @ConfigProperty — intégration vidocq-mps-core")
class RavelConfigPropertyIntegrationTest {

    @AfterEach
    void cleanup() {
        var resolver = ConfigProviderResolver.instance();
        var cfg = resolver.getConfig(getClass().getClassLoader());
        resolver.releaseConfig(cfg);
    }

    @Test
    @DisplayName("@ConfigProperty résout les valeurs via le container Vauban")
    void configPropertyResolvedInVaubanContainer() {
        var resolver = ConfigProviderResolver.instance();
        var config = resolver.getBuilder()
                .withSources(new MapConfigSource(Map.of(
                        "vidocq.app.name", "Vidocq MPS",
                        "vidocq.app.port", "9090"
                )))
                .forClassLoader(getClass().getClassLoader())
                .build();
        resolver.registerConfig(config, getClass().getClassLoader());

        var initializer = SeContainerInitializer.newInstance()
                .addBeanClasses(ConfigConsumerBean.class,
                        io.vidocq.ravel.cdi.ConfigCdiExtension.class);

        try (SeContainer container = initializer.initialize()) {
            var bean = container.select(ConfigConsumerBean.class).get();
            assertNotNull(bean);
            assertEquals("Vidocq MPS", bean.appName);
            assertEquals(Integer.valueOf(9090), bean.appPort);
            assertTrue(bean.appEnv.isEmpty(), "app.env absent → Optional.empty()");
        }
    }

    @Dependent
    public static class ConfigConsumerBean {
        @Inject
        @ConfigProperty(name = "vidocq.app.name", defaultValue = "unknown")
        public String appName;

        @Inject
        @ConfigProperty(name = "vidocq.app.port", defaultValue = "8080")
        public Integer appPort;

        @Inject
        @ConfigProperty(name = "vidocq.app.env")
        public Optional<String> appEnv;
    }

    private static final class MapConfigSource
            implements org.eclipse.microprofile.config.spi.ConfigSource {
        private final Map<String, String> values;

        MapConfigSource(Map<String, String> values) {
            this.values = Map.copyOf(values);
        }

        @Override public Map<String, String> getProperties() { return values; }
        @Override public Set<String> getPropertyNames() { return values.keySet(); }
        @Override public String getValue(String key) { return values.get(key); }
        @Override public String getName() { return "test-source"; }
        @Override public int getOrdinal() { return 1000; }
    }
}
