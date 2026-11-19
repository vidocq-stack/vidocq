/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
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



