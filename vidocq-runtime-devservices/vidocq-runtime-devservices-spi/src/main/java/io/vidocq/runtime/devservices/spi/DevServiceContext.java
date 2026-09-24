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
package io.vidocq.runtime.devservices.spi;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * The slice of the dev-mode environment a {@link DevService} sees: the application configuration
 * (so a provider can decide whether it {@link DevService#appliesWhen(DevServiceContext) applies} and
 * read its tuning keys), the project base directory (to resolve files such as a Keycloak realm
 * import) and a logger.
 *
 * <p>Configuration is resolved from, in decreasing precedence: properties already collected from earlier providers
 * and the goal's explicit {@code -D} / {@code vidocq.dev.systemProperties}, then the host JVM's system properties,
 * then environment variables. Keys starting with {@code vidocq.dev.} are also read from the application's
 * {@code vidocq.properties} and {@code application.properties}, after those; every other key never is, so that a
 * baked-in default such as {@code vidocq.pool.url} does not switch a dev service off.</p>
 */
public interface DevServiceContext {

    /**
     * Resolve a single configuration value.
     *
     * @param key the property key (e.g. {@code "vidocq.pool.url"}, {@code "vidocq.dev.keycloak.realm"})
     * @return the value if present and non-blank, otherwise {@link Optional#empty()}
     */
    Optional<String> property(String key);

    /**
     * The full, read-only view of the resolved configuration. Mainly for diagnostics.
     */
    Map<String, String> properties();

    /**
     * The project base directory ({@code ${project.basedir}}).
     */
    Path basedir();

    /**
     * Resolve a path relative to {@link #basedir()} (absolute inputs are returned unchanged).
     * Used to locate project-local assets such as {@code docker/keycloak/arago-realm.json}.
     *
     * @param relative a base-dir-relative or absolute path
     * @return the resolved absolute path
     */
    Path resolve(String relative);

    /**
     * A logger bound to the dev-mode output.
     */
    System.Logger log();
}
