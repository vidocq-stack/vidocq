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

import java.util.Set;

/**
 * Adapter exposing a {@link org.eclipse.microprofile.config.spi.ConfigSource}
 * PM in {@link ConfigSource} Vidocq.
 *
 * <p>Used by {@link RavelConfigSourceProvider} to publish the sources of
 * MP configuration (sys, env, microprofile-config.properties, user sources
 * custom, etc.) in the format expected by {@code VidocqConfig.getConfigSources()}.
 * Values ​​​​are delegated 1-for-1 to the underlying {@code ConfigSource} MP
 * — it is he who decides the resolution (priority, profiles, expressions).</p>
 *
 * <p>The delegate is kept as a reference; any hot change of a
 * MP {@code ConfigSource} (rare, but possible via {@code ConfigBuilder}) is
 * reflected immediately.</p>
 */
final class MpConfigSourceAdapter implements ConfigSource {

    private final org.eclipse.microprofile.config.spi.ConfigSource delegate;

    MpConfigSourceAdapter(org.eclipse.microprofile.config.spi.ConfigSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public int getOrdinal() {
        return delegate.getOrdinal();
    }

    @Override
    public String getValue(String key) {
        return delegate.getValue(key);
    }

    @Override
    public Set<String> getPropertyNames() {
        return delegate.getPropertyNames();
    }
}
