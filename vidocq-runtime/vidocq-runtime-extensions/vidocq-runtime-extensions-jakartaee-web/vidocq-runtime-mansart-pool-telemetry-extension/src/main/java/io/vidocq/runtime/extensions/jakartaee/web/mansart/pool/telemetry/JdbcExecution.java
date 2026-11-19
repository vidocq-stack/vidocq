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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.telemetry;

import java.sql.SQLException;

/**
 * How a statement runs: the generated {@code Delegating*} statements hand each {@code execute*} call, with the SQL it
 * runs, to the connection's execution, which times it ({@link TracingExecution}).
 */
interface JdbcExecution {

    /**
     * Runs {@code call}, the statement's own {@code execute*}, and returns its result.
     *
     * @param sql      the SQL the call runs; {@code null} when unknown
     * @param prepared whether {@code sql} is a prepared or callable statement's, with {@code ?} for its values: kept as
     *                 is, where a plain statement's literals are replaced
     */
    <R> R execute(String sql, boolean prepared, SqlCall<R> call) throws SQLException;

    /** One {@code execute*} call of the wrapped statement. */
    @FunctionalInterface
    interface SqlCall<R> {
        R call() throws SQLException;
    }
}
