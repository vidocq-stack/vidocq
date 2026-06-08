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
package io.vidocq.heisenberg.cdi.internal;

import java.util.ServiceLoader;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;

/**
 * ServiceLoader bridge to the MP Config resolver actually present on the classpath (Ravel).
 */
public final class HeisenbergAutoDiscovery extends ConfigProviderResolver {

    private ConfigProviderResolver delegate;

    // Public no-arg constructor required by ServiceLoader
    public HeisenbergAutoDiscovery() {
        // Lazy initialization to avoid circular ServiceLoader calls
    }

    @Override
    public Config getConfig() {
        return getDelegate().getConfig();
    }

    @Override
    public Config getConfig(ClassLoader classLoader) {
        return getDelegate().getConfig(classLoader);
    }

    @Override
    public ConfigBuilder getBuilder() {
        return getDelegate().getBuilder();
    }

    @Override
    public void registerConfig(Config config, ClassLoader classLoader) {
        getDelegate().registerConfig(config, classLoader);
    }

    @Override
    public void releaseConfig(Config config) {
        getDelegate().releaseConfig(config);
    }

    private ConfigProviderResolver getDelegate() {
        if (delegate == null) {
            delegate = discoverDelegate();
        }
        return delegate;
    }

    private ConfigProviderResolver discoverDelegate() {
        ServiceLoader<ConfigProviderResolver> loaders = ServiceLoader.load(ConfigProviderResolver.class);
        for (ConfigProviderResolver resolver : loaders) {
            if (!resolver.getClass().equals(HeisenbergAutoDiscovery.class)) {
                return resolver;
            }
        }
        throw new IllegalStateException("No MP ConfigProviderResolver found besides HeisenbergAutoDiscovery");
    }
}
