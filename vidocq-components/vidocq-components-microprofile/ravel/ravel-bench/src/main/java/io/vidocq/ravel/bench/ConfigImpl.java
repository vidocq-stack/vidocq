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
 * MicroProfile Config implementation selector for JMH benchmarks.
 *
 * <p>Builds an equivalent {@link Config} on the Ravel side and the Smallrye side from
 * the same set of properties, deliberately avoiding discoverable sources
 * (system properties / env vars) to measure only the cost of the local cascade.</p>
 *
 * <p><b>Important</b>: Ravel and Smallrye are both on the bench classpath.
 * To avoid depending on {@code ServiceLoader} discovery order, the
 * {@link ConfigProviderResolver} of each implementation is instantiated explicitly
 * by fully-qualified name (reflection only in {@code @Setup}, never in a {@code @Benchmark}).</p>
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
            // SmallRyeConfigBuilder does not need the resolver to be instantiated.
            ConfigSource source = new PropertiesConfigSource(new HashMap<>(properties), "bench-smallrye", 1000);
            return new SmallRyeConfigBuilder().withSources(source).build();
        }
    };

    private final String resolverFqn;

    ConfigImpl(String resolverFqn) {
        this.resolverFqn = resolverFqn;
    }

    public abstract Config build(Map<String, String> properties);

    /** Loads the implementation's resolver by fully-qualified name, ignoring other providers. */
    private static ConfigProviderResolver locateResolver(ConfigImpl impl) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (ConfigProviderResolver candidate : ServiceLoader.load(ConfigProviderResolver.class, cl)) {
            if (candidate.getClass().getName().equals(impl.resolverFqn)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No ConfigProviderResolver found for " + impl + " (fqn=" + impl.resolverFqn + ")");
    }
}



