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

import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import io.vidocq.mansart.transactions.core.SinglePhaseResource;
import jakarta.transaction.RollbackException;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.Test;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MANSART-007 — last-resource-commit optimisation in the two-phase path.
 *
 * <p>A {@link SinglePhaseResource} (JDBC local transaction wrapped as an XAResource) cannot
 * really prepare: its commit IS the decision. In a mixed transaction the coordinator must
 * therefore commit single-phase resources <b>first</b> — while the real XA resources are still
 * merely prepared — and, if that commit fails, roll the prepared XA resources back instead of
 * committing a half-applied outcome.
 */
class LastResourceCommitOrderTest {

    /** Records calls; implements the marker so the TM applies LRCO ordering. */
    static class RecordingSinglePhase implements SinglePhaseResource {
        final String name;
        final List<String> log;
        final boolean failOnCommit;

        RecordingSinglePhase(String name, List<String> log, boolean failOnCommit) {
            this.name = name;
            this.log = log;
            this.failOnCommit = failOnCommit;
        }

        @Override public void start(Xid xid, int flags) { log.add(name + ".start"); }
        @Override public void end(Xid xid, int flags)   { log.add(name + ".end"); }
        @Override public int  prepare(Xid xid)          { log.add(name + ".prepare"); return XA_OK; }
        @Override public void commit(Xid xid, boolean onePhase) throws XAException {
            log.add(name + ".commit");
            if (failOnCommit) throw new XAException(XAException.XAER_RMERR);
        }
        @Override public void rollback(Xid xid)               { log.add(name + ".rollback"); }
        @Override public void forget(Xid xid)                 { /* no-op */ }
        @Override public Xid[] recover(int flag)              { return new Xid[0]; }
        @Override public boolean isSameRM(XAResource other)   { return this == other; }
        @Override public int     getTransactionTimeout()      { return 0; }
        @Override public boolean setTransactionTimeout(int s) { return false; }
    }

    static class RecordingXa implements XAResource {
        final String name;
        final List<String> log;

        RecordingXa(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override public void start(Xid xid, int flags) { log.add(name + ".start"); }
        @Override public void end(Xid xid, int flags)   { log.add(name + ".end"); }
        @Override public int  prepare(Xid xid)          { log.add(name + ".prepare"); return XA_OK; }
        @Override public void commit(Xid xid, boolean onePhase) { log.add(name + ".commit"); }
        @Override public void rollback(Xid xid)               { log.add(name + ".rollback"); }
        @Override public void forget(Xid xid)                 { /* no-op */ }
        @Override public Xid[] recover(int flag)              { return new Xid[0]; }
        @Override public boolean isSameRM(XAResource other)   { return this == other; }
        @Override public int     getTransactionTimeout()      { return 0; }
        @Override public boolean setTransactionTimeout(int s) { return false; }
    }

    @Test
    void singlePhaseResourceCommitsBeforeRealXaResources() throws Exception {
        List<String> log = new ArrayList<>();
        TransactionManager tm = new MansartTransactionManager();

        tm.begin();
        tm.getTransaction().enlistResource(new RecordingXa("xa1", log));
        tm.getTransaction().enlistResource(new RecordingSinglePhase("local", log, false));
        tm.getTransaction().enlistResource(new RecordingXa("xa2", log));
        tm.commit();

        int local = log.indexOf("local.commit");
        int xa1 = log.indexOf("xa1.commit");
        int xa2 = log.indexOf("xa2.commit");
        assertThat(local).as("single-phase resource must commit").isNotNegative();
        assertThat(xa1).as("xa1 must commit").isNotNegative();
        assertThat(xa2).as("xa2 must commit").isNotNegative();
        assertThat(local)
                .as("the single-phase (LRCO) resource must commit BEFORE the real XA resources")
                .isLessThan(xa1)
                .isLessThan(xa2);
    }

    @Test
    void failingSinglePhaseCommitRollsBackPreparedXaResources() throws Exception {
        List<String> log = new ArrayList<>();
        TransactionManager tm = new MansartTransactionManager();

        tm.begin();
        tm.getTransaction().enlistResource(new RecordingXa("xa1", log));
        tm.getTransaction().enlistResource(new RecordingSinglePhase("local", log, true));
        tm.getTransaction().enlistResource(new RecordingXa("xa2", log));

        assertThatThrownBy(tm::commit)
                .as("a failed last-resource commit is a rollback, not a heuristic UNKNOWN")
                .isInstanceOf(RollbackException.class);

        assertThat(log).contains("local.commit", "xa1.rollback", "xa2.rollback");
        assertThat(log)
                .as("prepared XA resources must be rolled back, never committed")
                .doesNotContain("xa1.commit", "xa2.commit");
    }
}
