package io.vidocq.runtime.ext.ravel;

import io.vidocq.runtime.spi.config.ConfigSource;
import io.vidocq.runtime.spi.config.ConfigSourceProvider;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vérifie que {@link RavelConfigSourceProvider} expose les
 * {@code org.eclipse.microprofile.config.spi.ConfigSource} (incluant les sources
 * MP custom enregistrées par l'utilisateur) en {@link ConfigSource} Vidocq.
 *
 * <p>Test à granularité unité : on instancie directement le provider et on
 * inspecte ses sources, sans passer par {@code VidocqConfigImpl}. C'est
 * suffisant pour garantir que côté extension, la délégation à MP Config est
 * fidèle ; l'agrégation côté {@code VidocqConfigImpl} via ServiceLoader est
 * validée par les tests du module core.</p>
 */
@DisplayName("RavelConfigSourceProvider — délégation vers MP Config")
class RavelConfigSourceProviderTest {

    @AfterEach
    void cleanup() {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        resolver.releaseConfig(resolver.getConfig(getClass().getClassLoader()));
    }

    @Test
    @DisplayName("getConfigSources expose une source MP custom user")
    void exposesCustomMpSourceAsVidocqSource() {
        registerMpSource("custom", 1000, Map.of("vidocq.test.x", "y"));

        ConfigSourceProvider provider = new RavelConfigSourceProvider();
        boolean foundCustom = false;
        for (ConfigSource src : provider.getConfigSources(getClass().getClassLoader())) {
            if ("custom".equals(src.getName())) {
                assertEquals(1000, src.getOrdinal());
                assertEquals("y", src.getValue("vidocq.test.x"));
                foundCustom = true;
                break;
            }
        }
        assertTrue(foundCustom, "La source MP custom doit être exposée via le provider");
    }

    @Test
    @DisplayName("getConfigSources inclut les built-in MP (Sys, Env, MP-properties)")
    void exposesBuiltinMpSources() {
        // Pas d'enregistrement custom — Ravel doit déjà avoir ses sources par défaut.
        ConfigSourceProvider provider = new RavelConfigSourceProvider();

        Set<String> names = new HashSet<>();
        for (ConfigSource src : provider.getConfigSources(getClass().getClassLoader())) {
            names.add(src.getName());
        }
        // Les noms exacts viennent de Ravel ; on vérifie au moins la présence
        // d'une source de type "system" et d'une "environment".
        boolean hasSys = names.stream().anyMatch(n -> n.toLowerCase().contains("system"));
        boolean hasEnv = names.stream().anyMatch(n -> n.toLowerCase().contains("env"));
        assertTrue(hasSys, "MP built-in System source attendue, got " + names);
        assertTrue(hasEnv, "MP built-in Environment source attendue, got " + names);
    }

    // ---- helpers ----

    private void registerMpSource(String name, int ordinal, Map<String, String> values) {
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        var config = resolver.getBuilder()
                .addDefaultSources()
                .withSources(new MapMpConfigSource(name, ordinal, values))
                .forClassLoader(getClass().getClassLoader())
                .build();
        resolver.registerConfig(config, getClass().getClassLoader());
    }

    private static final class MapMpConfigSource
            implements org.eclipse.microprofile.config.spi.ConfigSource {
        private final String name;
        private final int ordinal;
        private final Map<String, String> values;

        MapMpConfigSource(String name, int ordinal, Map<String, String> values) {
            this.name = name;
            this.ordinal = ordinal;
            this.values = Map.copyOf(values);
        }

        @Override public Map<String, String> getProperties() { return values; }
        @Override public Set<String> getPropertyNames() { return values.keySet(); }
        @Override public String getValue(String key) { return values.get(key); }
        @Override public String getName() { return name; }
        @Override public int getOrdinal() { return ordinal; }
    }
}
