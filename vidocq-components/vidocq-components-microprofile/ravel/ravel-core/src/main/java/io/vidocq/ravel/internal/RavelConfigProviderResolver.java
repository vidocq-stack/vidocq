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
 * MP Config 3.1 §3.5 implementation of ConfigProviderResolver; one registry entry
 * per {@link ClassLoader}.
 *
 * <p>Discovered through {@link java.util.ServiceLoader} using both
 * {@code META-INF/services/...ConfigProviderResolver} and the JPMS
 * {@code provides ... with} declaration.</p>
 *
 * <p><b>Thread-safety</b>: uses {@link ConcurrentHashMap} and atomic
 * {@code computeIfAbsent}; no {@code synchronized} and no {@code ThreadLocal}.</p>
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
        // Remove by value identity, independent of ClassLoader key.
        for (Map.Entry<ClassLoader, Config> entry : registry.entrySet()) {
            if (entry.getValue() == config) {
                registry.remove(entry.getKey(), config);
                return;
            }
        }
        // Silent no-op if config is not found in the registry.
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
