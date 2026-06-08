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
import io.vidocq.runtime.extensions.microprofile.ravel.ApplicationPropertiesConfigSource;
import io.vidocq.runtime.extensions.microprofile.ravel.RavelConfigSourceProvider;
import io.vidocq.runtime.extensions.microprofile.ravel.VidocqPropertiesConfigSource;

/**
 * Vidocq Runtime extension that plugs Ravel (MicroProfile Config 3.1) as the
 * config source provider for the runtime, and brings @ConfigProperty CDI
 * injection via the transitive {@code io.vidocq.ravel.cdi.vauban} BCE.
 *
 * <p>When this module is on the module path, {@code vidocq-runtime-core}'s
 * {@code VidocqConfigImpl} picks up {@link RavelConfigSourceProvider} via
 * {@link java.util.ServiceLoader} and routes all config reads through Ravel —
 * including the user's MP custom sources, profiles, and {@code ${}}
 * expressions.</p>
 */
module io.vidocq.runtime.extensions.microprofile.ravel {
    requires io.vidocq.runtime.spi;
    // Transitive so downstream modules can use @Inject @ConfigProperty without
    // re-declaring the Ravel CDI module themselves.
    requires transitive io.vidocq.ravel.cdi.vauban;

    exports io.vidocq.runtime.extensions.microprofile.ravel;

    // Vidocq runtime SPI: discovered by VidocqConfigImpl at boot. When this
    // provider is registered, the native ConfigSource ServiceLoader of
    // vidocq-runtime-core is bypassed entirely — Ravel takes over.
    provides io.vidocq.runtime.spi.config.ConfigSourceProvider
            with RavelConfigSourceProvider;

    // MP Config SPI: backwards-compatibility for the historical Vidocq files
    // (vidocq.properties, application.properties) — Ravel doesn't read them
    // by default, this restores the legacy behavior at the MP ordinal level.
    provides org.eclipse.microprofile.config.spi.ConfigSource
            with VidocqPropertiesConfigSource, ApplicationPropertiesConfigSource;
}
