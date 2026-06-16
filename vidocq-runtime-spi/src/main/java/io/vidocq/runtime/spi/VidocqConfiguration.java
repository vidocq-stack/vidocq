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
package io.vidocq.runtime.spi;

import java.util.Optional;
import java.util.Set;

/**
 * Configuration accessible to Vidocq extensions.
 * <p>
 * The properties are resolved from (in order of priority):
 * <ol>
 *   <li>System properties ({@code -Dkey=value})</li>
 *   <li>Environment variables</li>
 *   <li>Classpath {@code vidocq.properties} file</li>
 * </ol>
 *
 * <p><b>Configuration accessible to Vidocq extensions.</b>
 * Properties are resolved from system properties, environment variables,
 * and {@code vidocq.properties} classpath resource (in that order).</p>
 */
public interface VidocqConfiguration {

    /**
     * Retrieves a property by its key.
     * <p>Retrieve a property by key.</p>
     */
    Optional<String> property(String key);

    /**
     * All known configuration keys, across every source.
     *
     * <p>Lets an extension discover a dynamic key family whose names are not known ahead of time —
     * e.g. {@code vidocq.pool.<name>.url} for multi-datasource. The default returns an empty set so
     * a minimal hand-rolled implementation that only answers {@link #property(String)} keeps
     * compiling; the runtime facade overrides it to enumerate every registered config source.</p>
     */
    default Iterable<String> propertyNames() {
        return Set.of();
    }

    /**
     * Retrieves a property with a default value.
     * <p>Retrieve a property with a default value.</p>
     */
    default String property(String key, String defaultValue) {
        return property(key).orElse(defaultValue);
    }

    /**
     * Retrieves a port for a given extension.
     * <p>Key consulted: {@code vidocq.<extensionName>.port}</p>
     * <p>Retrieve a port for a given extension.</p>
     */
    default int portFor(String extensionName, int defaultPort) {
        return property("vidocq." + extensionName + ".port")
                .map(Integer::parseInt)
                .orElse(defaultPort);
    }
}
