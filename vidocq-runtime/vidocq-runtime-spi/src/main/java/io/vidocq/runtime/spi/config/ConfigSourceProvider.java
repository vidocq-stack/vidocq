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
package io.vidocq.runtime.spi.config;

/**
 * Provides a set of {@link ConfigSource} instances to the Vidocq runtime.
 *
 * <p>Discovered via {@link java.util.ServiceLoader} when
 * {@code VidocqConfigImpl} starts. This SPI allows Vidocq to integrate a
 * third-party configuration engine (MicroProfile Config via Ravel, HashiCorp Consul,
 * Spring Cloud Config, etc.) without coupling {@code vidocq-runtime-core} to
 * the chosen implementation.</p>
 *
 * <p><b>Replacement semantics</b> — if <em>at least one</em>
 * {@code ConfigSourceProvider} is registered at startup, its sources
 * <b>completely replace</b> the native {@link ConfigSource} Vidocq
 * discovered via {@code ServiceLoader<ConfigSource>}. The provider is therefore
 * responsible for providing the necessary substitutes (system properties,
 * environment variables, properties files, etc.) — Vidocq does not mix
 * both to avoid double counting and preserve semantics
 * of ordinals from the external ecosystem (e.g. MP Config priorities).</p>
 *
 * <p>When several providers are present, the union of their sources is used,
 * sorted by descending {@link ConfigSource#getOrdinal()}.</p>
 *
 * <p><b>Typical use case</b> — the extension
 * {@code vidocq-runtime-ravel-config-extension} provides a
 * {@code RavelConfigSourceProvider} which iterates over the
 * {@code org.eclipse.microprofile.config.spi.ConfigSource} exposed by
 * {@code ConfigProvider.getConfig()} and wraps them as Vidocq {@link ConfigSource}
 * instances. When the extension is on the module path, Ravel takes control; when
 * it is absent, the 4 native Vidocq sources (Sys, Env, ExternalFile,
 * PropertiesFile) are used.</p>
 */
public interface ConfigSourceProvider {

    /**
     * Diagnostic name of the provider, used for logs and introspection.
     */
    String getName();

    /**
     * Sources contributed by this provider.
     *
     * <p>The current {@link ClassLoader} is passed so external engines
     * (MP Config, Spring, etc.) can resolve their configuration in the
     * proper loading context.</p>
     */
    Iterable<ConfigSource> getConfigSources(ClassLoader cl);
}
