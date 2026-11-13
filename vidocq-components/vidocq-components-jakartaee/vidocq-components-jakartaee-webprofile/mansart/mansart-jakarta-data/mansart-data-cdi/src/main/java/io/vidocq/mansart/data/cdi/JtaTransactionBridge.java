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

import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.core.TransactionBridge;
import io.vidocq.mansart.transactions.jdbc.JtaConnections;
import io.vidocq.mansart.transactions.jdbc.JtaDataSource;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * JTA implementation of {@link TransactionBridge} (MANSART-007): inside an active transaction,
 * every datasource used by a repository gets ONE connection, enlisted in the transaction —
 * the transaction outcome then governs the writes; the connection is closed after completion.
 *
 * <p>The connection is the one {@link JtaConnections} keeps for the transaction and the datasource
 * (MANSART-066): a repository shares it with whatever else works on the same datasource in the
 * same transaction through a {@link JtaDataSource} — a persistence unit, plain JDBC code. The
 * enlistment rules (XA-first, a single-phase resource otherwise, a pooled XA connection given back
 * after completion — MANSART-065) are documented there.
 */
final class JtaTransactionBridge implements TransactionBridge {

    private final JtaConnections connections;

    JtaTransactionBridge(TransactionManager tm, TransactionSynchronizationRegistry tsr) {
        this.connections = new JtaConnections(tm, tsr);
    }

    @Override
    public Connection connectionFor(DataSource dataSource) {
        try {
            return connections.enlisted(dataSource);
        } catch (SQLException e) {
            throw new MansartDataException(
                    "Failed to enlist a connection in the active transaction", e);
        }
    }
}
