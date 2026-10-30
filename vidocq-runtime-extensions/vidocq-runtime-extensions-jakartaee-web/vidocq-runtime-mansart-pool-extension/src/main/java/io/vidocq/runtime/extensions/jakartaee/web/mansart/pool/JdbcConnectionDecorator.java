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

/**
 * Wraps each {@link Connection} the application gets from a Vidocq {@link javax.sql.DataSource}, the {@code @Default}
 * pool and every {@code @Named} one. A telemetry glue module provides one ({@code provides … with}) to emit a span per
 * statement (BUG-20261030-25); without any provider, the pool's own connection is returned and nothing is added on the
 * path.
 *
 * <p>A decorator returns {@code connection} itself, or a wrapper delegating to it. It must not block: it runs on the
 * caller's (virtual) thread, inside {@code getConnection()}.</p>
 */
@FunctionalInterface
public interface JdbcConnectionDecorator {

    /**
     * @param dataSourceName the name of the datasource, {@code default} for the {@code @Default} pool
     * @param connection     the pool's connection, or the previous decorator's wrapper
     * @return the connection the application gets
     */
    Connection decorate(String dataSourceName, Connection connection) throws SQLException;
}
