/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.bench;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Sélecteur d'implémentation MicroProfile Config pour les benchmarks JMH.
 *
 * <p>Construit un {@link Config} équivalent côté Ravel et côté Smallrye à partir
 * du même jeu de propriétés, en évitant volontairement les sources discoverables
 * (system properties / env vars) pour ne mesurer que le coût de la cascade locale.</p>
 *
 * <p><b>Important</b> : Ravel et Smallrye sont tous deux sur le classpath du bench.
 * Pour ne pas dépendre de l'ordre de découverte du {@code ServiceLoader}, on instancie
 * explicitement le {@link ConfigProviderResolver} de chaque implémentation par nom
 * qualifié (réflexion uniquement au {@code @Setup}, jamais dans un {@code @Benchmark}).</p>
 */
public enum ConfigImpl {
    RAVEL("io.vidocq.ravel.internal.RavelConfigProviderResolver") {
        @Override
        public Config build(Map<String, String> properties) {
            ConfigSource source = new BenchSources.InMemorySource("bench-ravel", properties, 1000);
            ConfigBuilder builder = locateResolver(this).getBuilder();
            return builder.withSources(source).build();
        }
    },
    SMALLRYE(null) {
        @Override
        public Config build(Map<String, String> properties) {
            // SmallRyeConfigBuilder n'a pas besoin du resolver pour être instancié.
            ConfigSource source = new PropertiesConfigSource(new HashMap<>(properties), "bench-smallrye", 1000);
            return new SmallRyeConfigBuilder().withSources(source).build();
        }
    };

    private final String resolverFqn;

    ConfigImpl(String resolverFqn) {
        this.resolverFqn = resolverFqn;
    }

    public abstract Config build(Map<String, String> properties);

    /** Charge le resolver de l'implémentation par nom qualifié, en ignorant les autres providers. */
    private static ConfigProviderResolver locateResolver(ConfigImpl impl) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (ConfigProviderResolver candidate : ServiceLoader.load(ConfigProviderResolver.class, cl)) {
            if (candidate.getClass().getName().equals(impl.resolverFqn)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "ConfigProviderResolver introuvable pour " + impl + " (fqn=" + impl.resolverFqn + ")");
    }
}



