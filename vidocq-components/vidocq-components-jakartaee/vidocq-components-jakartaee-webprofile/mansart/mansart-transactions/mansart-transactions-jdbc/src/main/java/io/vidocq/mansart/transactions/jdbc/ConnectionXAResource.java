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
package io.vidocq.mansart.transactions.jdbc;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Adapts a plain {@link Connection} to {@link XAResource} so it can be enrolled into a
 * Mansart-coordinated transaction. Single-phase commit only — this is the « LRCO »
 * (last-resource-commit-optimisation) pattern adapted for Mansart's 1PC degenerate path.
 *
 * <p>How it works :
 * <ul>
 *   <li>{@link #start} flips the connection to {@code autoCommit=false} so any subsequent
 *       statement is buffered into the JDBC local transaction.</li>
 *   <li>{@link #end} is a no-op — the JDBC local TX state is preserved until commit/rollback.</li>
 *   <li>{@link #prepare} reports {@link #XA_OK} but DOES NOT flush — there is no real prepare on
 *       a non-XA connection. If you enrol two of these, the first one to fail at commit-time will
 *       leave the others in an inconsistent state. Use a real XA driver for true 2PC.</li>
 *   <li>{@link #commit} delegates to {@link Connection#commit}.</li>
 *   <li>{@link #rollback} delegates to {@link Connection#rollback}.</li>
 * </ul>
 *
 * <p>The original autoCommit value is restored at completion so downstream pooled-connection
 * recyclers don't see it dirty.
 */
public final class ConnectionXAResource implements XAResource {

    private final Connection connection;
    private boolean originalAutoCommit;
    private int transactionTimeout;

    public ConnectionXAResource(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override
    public void start(Xid xid, int flags) throws XAException {
        try {
            originalAutoCommit = connection.getAutoCommit();
            if (originalAutoCommit) {
                connection.setAutoCommit(false);
            }
        } catch (SQLException e) {
            throw xa(XAException.XAER_RMERR, e);
        }
    }

    @Override
    public void end(Xid xid, int flags) {
        // Local JDBC TX has no notion of suspend ; commit/rollback are the only exits.
    }

    @Override
    public int prepare(Xid xid) {
        // Best-effort vote — there is no real PREPARE on a non-XA connection. The TM will then
        // call commit() ; if that fails, we report SystemException up but cannot atomically
        // coordinate with siblings. Mixed 1PC + real-XA enrolment is OUTSIDE the contract here.
        return XA_OK;
    }

    @Override
    public void commit(Xid xid, boolean onePhase) throws XAException {
        try {
            connection.commit();
        } catch (SQLException e) {
            throw xa(onePhase ? XAException.XA_RBROLLBACK : XAException.XAER_RMERR, e);
        } finally {
            restoreAutoCommit();
        }
    }

    @Override
    public void rollback(Xid xid) throws XAException {
        try {
            connection.rollback();
        } catch (SQLException e) {
            throw xa(XAException.XAER_RMERR, e);
        } finally {
            restoreAutoCommit();
        }
    }

    @Override
    public void forget(Xid xid) {
        // No heuristic state kept — nothing to forget.
    }

    @Override
    public Xid[] recover(int flag) {
        // No persistent log on a plain JDBC connection — nothing to recover.
        return new Xid[0];
    }

    @Override
    public boolean isSameRM(XAResource other) {
        return this == other;
    }

    @Override
    public int getTransactionTimeout() {
        return transactionTimeout;
    }

    @Override
    public boolean setTransactionTimeout(int seconds) {
        this.transactionTimeout = seconds;
        return true;
    }

    /** Best-effort restore — swallowed on close()d connections, the pool will discard them. */
    private void restoreAutoCommit() {
        try {
            if (!connection.isClosed() && originalAutoCommit) {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ignored) { /* best effort */ }
    }

    private static XAException xa(int errorCode, SQLException cause) {
        XAException ex = new XAException(errorCode);
        ex.initCause(cause);
        return ex;
    }
}
