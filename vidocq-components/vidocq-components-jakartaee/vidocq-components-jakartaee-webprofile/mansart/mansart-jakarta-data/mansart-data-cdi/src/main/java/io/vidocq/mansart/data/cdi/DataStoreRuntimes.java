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
package io.vidocq.mansart.data.cdi;

import io.vidocq.mansart.data.core.RepositoryRuntime;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * The {@link RepositoryRuntime} of each routed data store of one CDI container, so that several repositories
 * targeting the same store share one runtime.
 *
 * <p>It is a singleton synthetic bean of the container, not a static map: a runtime wraps the {@code DataSource} bean
 * of its container, so a map that outlives the container (the JVM, across the application layers {@code vidocq dev}
 * discards on a hot reload) kept every discarded layer reachable and handed the next one a runtime on the previous
 * layer's closed data source (MANSART-041). Created by {@link DataStoreRuntimesCreator}; internal.
 */
public final class DataStoreRuntimes {

    private final ConcurrentMap<String, RepositoryRuntime> byKey = new ConcurrentHashMap<>();

    DataStoreRuntimes() {}

    /** The runtime of {@code dataStore}, built once by {@code builder}. */
    RepositoryRuntime of(String dataStore, Function<String, RepositoryRuntime> builder) {
        return byKey.computeIfAbsent(dataStore, builder);
    }
}
