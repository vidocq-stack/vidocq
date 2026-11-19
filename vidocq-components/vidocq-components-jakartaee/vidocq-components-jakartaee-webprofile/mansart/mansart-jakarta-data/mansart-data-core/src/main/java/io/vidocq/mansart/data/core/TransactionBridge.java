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
package io.vidocq.mansart.data.core;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Links repository operations to an externally managed transaction (MANSART-007).
 *
 * <p>{@link ConnectionScope#withConnection(TransactionBridge, DataSource, ConnectionScope.SqlAction)}
 * consults the bridge before opening its per-operation autocommit connection: when the caller
 * runs inside an active transaction, the bridge returns the connection enlisted in it for the
 * given datasource — every repository operation of that transaction then shares it, and the
 * transaction outcome (commit/rollback) governs the writes.
 *
 * <p>This SPI keeps {@code mansart-data-core} free of any transaction-API dependency: the JTA
 * implementation lives in {@code mansart-data-cdi} ({@code JtaTransactionBridge}) and is only
 * installed when a {@code TransactionManager} is present in the deployment.
 */
public interface TransactionBridge {

    /**
     * The connection enlisted in the caller's active transaction for {@code dataSource}, or
     * {@code null} when no transaction is active (the caller then uses its own per-operation
     * autocommit connection, unchanged semantics).
     *
     * <p>Callers must <b>not</b> close the returned connection — the bridge owns its lifecycle
     * and closes it after transaction completion.
     */
    Connection connectionFor(DataSource dataSource);
}
