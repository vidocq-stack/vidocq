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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@link JdbcConnectionDecorator}s found through {@link ServiceLoader} when this class is first used, applied in
 * that order to every connection the Vidocq DataSources hand out.
 */
final class JdbcConnectionDecorators {

    /** The name {@link MansartPoolHolder}, the {@code @Default} pool, passes to the decorators. */
    static final String DEFAULT_NAME = "default";

    /** An immutable list, replaced as a whole by {@link #install(List)}. */
    private static final AtomicReference<List<JdbcConnectionDecorator>> DECORATORS = new AtomicReference<>(ServiceLoader
            .load(JdbcConnectionDecorator.class, JdbcConnectionDecorator.class.getClassLoader())
            .stream().map(ServiceLoader.Provider::get).toList());

    private JdbcConnectionDecorators() {
    }

    /** {@code connection} through every decorator, or itself when there is none. */
    static Connection decorate(String dataSourceName, Connection connection) throws SQLException {
        Connection current = connection;
        for (JdbcConnectionDecorator decorator : DECORATORS.get()) {
            current = decorator.decorate(dataSourceName, current);
        }
        return current;
    }

    /** Replaces the decorators; for the tests, where the module's own {@code provides} is not read. */
    static void install(List<JdbcConnectionDecorator> installed) {
        DECORATORS.set(List.copyOf(installed));
    }
}
