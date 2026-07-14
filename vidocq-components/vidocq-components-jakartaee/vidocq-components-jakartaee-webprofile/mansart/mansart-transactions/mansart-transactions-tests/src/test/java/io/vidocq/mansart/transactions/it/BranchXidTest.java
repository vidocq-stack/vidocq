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
package io.vidocq.mansart.transactions.it;

import io.vidocq.mansart.transactions.core.FileRecoveryLog;
import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import io.vidocq.mansart.transactions.core.RecoveryLog;
import io.vidocq.mansart.transactions.core.RecoveryReport;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XA branch identity (MANSART-007 follow-up). The XA spec mandates one branch per enlisted
 * resource: same global transaction id, DISTINCT branch qualifiers. Handing the same Xid to two
 * resources breaks resource managers that check branch uniqueness (XAER_DUPID when both
 * datasources reach the same RM) — and every per-branch call (end/prepare/commit/rollback) must
 * reuse the exact Xid the branch was started with.
 */
class BranchXidTest {

    /** Captures every Xid this resource sees, per operation. */
    static class XidCapturingResource implements XAResource {
        final List<Xid> started = new ArrayList<>();
        final List<Xid> prepared = new ArrayList<>();
        final List<Xid> committed = new ArrayList<>();
        final List<Xid> rolledBack = new ArrayList<>();
        final List<Xid> recoverable = new ArrayList<>();

        @Override public void start(Xid xid, int flags) { started.add(xid); }
        @Override public void end(Xid xid, int flags)   { /* recorded via started */ }
        @Override public int  prepare(Xid xid)          { prepared.add(xid); return XA_OK; }
        @Override public void commit(Xid xid, boolean onePhase) { committed.add(xid); }
        @Override public void rollback(Xid xid)              { rolledBack.add(xid); }
        @Override public void forget(Xid xid)                 { /* no-op */ }
        @Override public Xid[] recover(int flag)              { return recoverable.toArray(Xid[]::new); }
        @Override public boolean isSameRM(XAResource other)   { return this == other; }
        @Override public int     getTransactionTimeout()      { return 0; }
        @Override public boolean setTransactionTimeout(int s) { return false; }
    }

    private record FixedXid(int formatId, byte[] gtrid, byte[] bqual) implements Xid {
        @Override public int    getFormatId()            { return formatId; }
        @Override public byte[] getGlobalTransactionId() { return gtrid; }
        @Override public byte[] getBranchQualifier()     { return bqual; }
    }

    @Test
    void eachEnlistedResourceGetsItsOwnBranchQualifier() throws Exception {
        TransactionManager tm = new MansartTransactionManager();
        XidCapturingResource r1 = new XidCapturingResource();
        XidCapturingResource r2 = new XidCapturingResource();

        tm.begin();
        tm.getTransaction().enlistResource(r1);
        tm.getTransaction().enlistResource(r2);
        tm.commit();

        Xid b1 = r1.started.getFirst();
        Xid b2 = r2.started.getFirst();
        assertThat(b1.getGlobalTransactionId())
                .as("both branches share the global transaction id")
                .isEqualTo(b2.getGlobalTransactionId());
        assertThat(b1.getBranchQualifier())
                .as("each branch must have a DISTINCT branch qualifier (XA spec)")
                .isNotEqualTo(b2.getBranchQualifier());

        assertThat(r1.prepared).containsExactly(b1);
        assertThat(r2.prepared).containsExactly(b2);
        assertThat(r1.committed)
                .as("commit must reuse the exact Xid the branch was started with")
                .containsExactly(b1);
        assertThat(r2.committed).containsExactly(b2);
    }

    @Test
    void rollbackUsesEachBranchOwnXid() throws Exception {
        TransactionManager tm = new MansartTransactionManager();
        XidCapturingResource r1 = new XidCapturingResource();
        XidCapturingResource r2 = new XidCapturingResource();

        tm.begin();
        tm.getTransaction().enlistResource(r1);
        tm.getTransaction().enlistResource(r2);
        tm.rollback();

        assertThat(r1.rolledBack).containsExactly(r1.started.getFirst());
        assertThat(r2.rolledBack).containsExactly(r2.started.getFirst());
    }

    @Test
    void recoveryMatchesBranchesByGlobalTransactionId(@TempDir Path dir) throws Exception {
        // Simulated crash aftermath: the journal says COMMITTING for the GLOBAL xid (bqual 0),
        // while the driver holds two in-doubt BRANCHES of it (bqual 1 and 2). Reconciliation
        // must match on the global transaction id and complete BOTH branches.
        byte[] gtrid = {42};
        Path journal = dir.resolve("tx.log");
        try (FileRecoveryLog log = new FileRecoveryLog(journal)) {
            log.append(new RecoveryLog.Record(RecoveryLog.Type.PREPARED,
                    new FixedXid(MansartFormat.ID, gtrid, new byte[]{0})));
            log.append(new RecoveryLog.Record(RecoveryLog.Type.COMMITTING,
                    new FixedXid(MansartFormat.ID, gtrid, new byte[]{0})));
        }

        XidCapturingResource driver = new XidCapturingResource();
        driver.recoverable.add(new FixedXid(MansartFormat.ID, gtrid, new byte[]{1}));
        driver.recoverable.add(new FixedXid(MansartFormat.ID, gtrid, new byte[]{2}));

        MansartTransactionManager tm =
                new MansartTransactionManager(new FileRecoveryLog(journal));
        RecoveryReport report = tm.recover(driver);

        assertThat(driver.committed)
                .as("both in-doubt branches of the committing gtrid must be completed")
                .hasSize(2);
        assertThat(report.committed()).hasSize(2);
        assertThat(report.stillInDoubt()).isEmpty();
    }

    /** The format id MansartXid stamps on its Xids — read from a live transaction. */
    static final class MansartFormat {
        static final int ID = readFormatId();

        private static int readFormatId() {
            try {
                TransactionManager tm = new MansartTransactionManager();
                XidCapturingResource probe = new XidCapturingResource();
                tm.begin();
                tm.getTransaction().enlistResource(probe);
                tm.rollback();
                return probe.started.getFirst().getFormatId();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
