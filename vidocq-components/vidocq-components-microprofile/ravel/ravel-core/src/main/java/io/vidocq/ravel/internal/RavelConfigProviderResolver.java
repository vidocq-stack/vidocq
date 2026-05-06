/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Implémentation MP Config 3.1 §3.5 — singleton (un par {@link ClassLoader}).
 *
 * <p>Découvert via {@link java.util.ServiceLoader} grâce au descripteur
 * {@code META-INF/services/org.eclipse.microprofile.config.spi.ConfigProviderResolver}
 * et au {@code provides ... with} dans {@code module-info.java} (étape (i) du plan M1).</p>
 *
 * <p><b>Thread-safety</b> : registre {@link ConcurrentHashMap}, build atomique via
 * {@code computeIfAbsent}. Aucun {@code synchronized}, aucun {@code ThreadLocal} —
 * compatible virtual threads.</p>
 */
public final class RavelConfigProviderResolver extends ConfigProviderResolver {

    private final ConcurrentMap<ClassLoader, Config> registry = new ConcurrentHashMap<>();

    public RavelConfigProviderResolver() {
        // ServiceLoader-friendly
    }

    @Override
    public Config getConfig() {
        return getConfig(currentClassLoader());
    }

    @Override
    public Config getConfig(ClassLoader loader) {
        ClassLoader cl = loader != null ? loader : currentClassLoader();
        return registry.computeIfAbsent(cl, this::buildDefaultConfig);
    }

    @Override
    public ConfigBuilder getBuilder() {
        return new RavelConfigBuilder();
    }

    @Override
    public void registerConfig(Config config, ClassLoader classLoader) {
        Objects.requireNonNull(config, "config");
        ClassLoader cl = classLoader != null ? classLoader : currentClassLoader();
        registry.put(cl, config);
    }

    @Override
    public void releaseConfig(Config config) {
        Objects.requireNonNull(config, "config");
        // Suppression par identité de la valeur, indépendamment du ClassLoader.
        for (Map.Entry<ClassLoader, Config> entry : registry.entrySet()) {
            if (entry.getValue() == config) {
                registry.remove(entry.getKey(), config);
                return;
            }
        }
        // No-op silencieux si la config n'est pas dans le registre.
    }

    // -------- internals --------

    private Config buildDefaultConfig(ClassLoader cl) {
        return new RavelConfigBuilder()
                .forClassLoader(cl)
                .addDefaultSources()
                .addDiscoveredSources()
                .addDiscoveredConverters()
                .build();
    }

    private static ClassLoader currentClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : RavelConfigProviderResolver.class.getClassLoader();
    }
}
