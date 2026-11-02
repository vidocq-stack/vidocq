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
package io.vidocq.runtime.extensions.microprofile.ravel;

import io.vidocq.ravel.spi.LayeredConfigProviderResolver;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;

/**
 * Releases the MicroProfile Config of the application layer when the layer stops.
 *
 * <p>Ravel keeps one {@code Config} per class loader in a registry that lives as long as the JVM, and nothing
 * removes an entry but a release. A {@code vidocq dev} hot reload boots a fresh layer, with a class loader of its
 * own, and the container reads the configuration under loaders it builds on top of it, so without a release every
 * reload kept the stopped layer reachable from that registry.</p>
 */
public final class RavelConfigExtension implements VidocqExtension {

    /** The class loader of the layer being booted; {@code null} before configure and after stop. */
    private ClassLoader layerLoader;
    /** The configuration this boot registered for {@link #layerLoader}; {@code null} like it. */
    private Config layerConfig;

    @Override
    public String name() {
        return "ravel-config";
    }

    /**
     * Takes hold of the layer being booted: the context class loader is the layer's, and the runtime has read the
     * configuration through it already, so its configuration is the entry the registry holds.
     */
    @Override
    public void configure(VidocqConfiguration config) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = RavelConfigExtension.class.getClassLoader();
        }
        layerLoader = loader;
        layerConfig = ConfigProviderResolver.instance().getConfig(loader);
    }

    /**
     * Takes the configuration of the layer out of the registry, once. The container reads its configuration under
     * class loaders it builds on top of the layer's, so Ravel's resolver releases the layer's loader and every
     * loader below it; any other resolver gets the one configuration this boot registered. Nothing to do when the
     * boot never got to {@link #configure}.
     */
    @Override
    public void onStop() {
        ClassLoader loader = layerLoader;
        Config config = layerConfig;
        layerLoader = null;
        layerConfig = null;
        if (loader == null) {
            return;
        }
        ConfigProviderResolver resolver = ConfigProviderResolver.instance();
        if (resolver instanceof LayeredConfigProviderResolver layered) {
            layered.releaseConfigs(loader);
        } else {
            resolver.releaseConfig(config);
        }
    }
}
