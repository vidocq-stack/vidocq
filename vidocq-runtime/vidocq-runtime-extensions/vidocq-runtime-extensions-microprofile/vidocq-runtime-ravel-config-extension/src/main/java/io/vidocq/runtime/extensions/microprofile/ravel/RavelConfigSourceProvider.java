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

import io.vidocq.runtime.spi.config.ConfigSource;
import io.vidocq.runtime.spi.config.ConfigSourceProvider;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;

/**
 * Ravel implementation of the Vidocq SPI {@link ConfigSourceProvider}.
 *
 * <p>When the runtime starts, {@code VidocqConfigImpl} discovers this class via
 * {@link java.util.ServiceLoader}. Its presence in the path toggle module
 * Vidocq in delegation mode: the native Vidocq {@link ConfigSource} (Sys, Env,
 * ExternalFile, PropertiesFile) are ignored in favor of the sources exposed by
 * MP Config — sys properties (ordinal 400), env vars (300),
 * {@code microprofile-config.properties} (100), {@code vidocq.properties} (105),
 * {@code application.properties} (100), plus all MP custom user sources
 * ({@code %dev.} profiles, {@code ${}} expressions, etc.).</p>
 *
 * <p>The extension also brings the ECB {@code ConfigCdiExtension} via the
 * transitive {@code ravel-cdi-vauban}, which automatically activates
 * {@code @Inject @ConfigProperty} in the Vauban container.</p>
 */
public final class RavelConfigSourceProvider implements ConfigSourceProvider {

    @Override
    public String getName() {
        return "RavelConfigSourceProvider";
    }

    @Override
    public Iterable<ConfigSource> getConfigSources(ClassLoader cl) {
        ClassLoader loader = cl != null ? cl : Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = RavelConfigSourceProvider.class.getClassLoader();

        Config mpConfig = ConfigProviderResolver.instance().getConfig(loader);

        List<ConfigSource> wrapped = new ArrayList<>();
        for (org.eclipse.microprofile.config.spi.ConfigSource mp : mpConfig.getConfigSources()) {
            wrapped.add(new MpConfigSourceAdapter(mp));
        }
        return wrapped;
    }
}
