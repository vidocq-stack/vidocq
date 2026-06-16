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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry mapping a datasource name to its {@link DataSource}. {@link
 * MansartPoolExtension} populates it from {@code vidocq.pool.<name>.*} pools, and the compile-time
 * generated {@code @Named} holders (see {@link AbstractNamedDataSourceHolder}) read from it.
 *
 * <p>The registry is intentionally neutral — any producer may register a DataSource under a name;
 * the Mansart pool extension is merely the default producer for {@code vidocq.pool.*}-configured
 * pools. This keeps the {@code @Named} resolution path (Jakarta Data's {@code DataStoreResolver}
 * and direct {@code @Inject @Named}) decoupled from how the pools are built.
 */
public final class NamedDataSourceRegistry {

    private static final Map<String, DataSource> SOURCES = new ConcurrentHashMap<>();

    private NamedDataSourceRegistry() {
    }

    /** Registers (or replaces) the {@link DataSource} exposed under {@code name}. */
    public static void register(String name, DataSource dataSource) {
        SOURCES.put(name, dataSource);
    }

    /** Removes the mapping for {@code name}, if present. */
    public static void unregister(String name) {
        SOURCES.remove(name);
    }

    /**
     * Returns the {@link DataSource} registered under {@code name}.
     *
     * @throws IllegalStateException if none is registered — typically because the corresponding
     *                               {@code vidocq.pool.<name>.url} is not set.
     */
    public static DataSource require(String name) {
        DataSource ds = SOURCES.get(name);
        if (ds == null) {
            throw new IllegalStateException(
                    "No DataSource registered for dataStore '" + name + "' — is vidocq.pool."
                            + name + ".url set?");
        }
        return ds;
    }
}
