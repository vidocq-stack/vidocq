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
 * {@code META-INF/services/...ConfigProviderResolver} and the Java Modules
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
