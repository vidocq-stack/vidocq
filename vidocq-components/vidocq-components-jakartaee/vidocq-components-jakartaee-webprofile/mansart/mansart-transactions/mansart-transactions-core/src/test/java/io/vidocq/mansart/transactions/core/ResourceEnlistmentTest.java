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
package io.vidocq.mansart.transactions.core;

import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.transaction.xa.XAResource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4 — Single + multi {@link XAResource} enrolment, 1PC degenerate path, 2PC full path,
 * rollback-on-prepare. Spec : JTA 2.0 §3.3.4 / §3.4.
 */
class ResourceEnlistmentTest {

    private final TransactionManager tm = new MansartTransactionManager();

    @AfterEach
    void cleanup() throws Exception {
        if (tm.getStatus() != Status.STATUS_NO_TRANSACTION) {
            try { tm.rollback(); } catch (Exception _) { /* best effort */ }
        }
    }

    @Test
    void enlistOutsideTxFails() {
        var r = new RecordingXAResource("a", new ArrayList<>());
        assertThatThrownBy(() -> {
            // No TX active — Transaction is null, so enlist isn't even reachable through
            // the normal flow. The user-facing failure is "no TX" via TransactionManager.
            tm.getTransaction().enlistResource(r);
        }).isInstanceOf(NullPointerException.class);
    }

    @Test
    void enlistResourceCallsXaStart() throws Exception {
        List<String> log = new ArrayList<>();
        var r = new RecordingXAResource("a", log);

        tm.begin();
        boolean enlisted = tm.getTransaction().enlistResource(r);
        assertThat(enlisted).isTrue();
        assertThat(log).containsExactly("a.start");
        tm.rollback();
    }

    @Test
    void singleResourceCommitUsesOnePhase() throws Exception {
        List<String> log = new ArrayList<>();
        var r = new RecordingXAResource("a", log);

        tm.begin();
        tm.getTransaction().enlistResource(r);
        tm.commit();

        // Spec : single-resource degenerate 2PC = one-phase commit, no prepare.
        assertThat(log).containsExactly(
                "a.start",
                "a.end(TMSUCCESS)",
                "a.commit(onePhase=true)");
    }

    @Test
    void twoResourcesCommitRunsFullTwoPhase() throws Exception {
        List<String> log = new ArrayList<>();
        var a = new RecordingXAResource("a", log);
        var b = new RecordingXAResource("b", log);

        tm.begin();
        tm.getTransaction().enlistResource(a);
        tm.getTransaction().enlistResource(b);
        tm.commit();

        assertThat(log).containsExactly(
                "a.start", "b.start",
                "a.end(TMSUCCESS)", "b.end(TMSUCCESS)",
                "a.prepare", "b.prepare",
                "a.commit(onePhase=false)", "b.commit(onePhase=false)");
    }

    @Test
    void prepareReturningRollbackVoteRollsBackEveryone() throws Exception {
        List<String> log = new ArrayList<>();
        var a = new RecordingXAResource("a", log);
        var b = new RecordingXAResource("b", log);
        b.prepareThrowsRollback = true;

        tm.begin();
        tm.getTransaction().enlistResource(a);
        tm.getTransaction().enlistResource(b);

        assertThatThrownBy(tm::commit).isInstanceOf(RollbackException.class);

        // Once b.prepare voted rollback, every resource that had voted YES (here just 'a')
        // must be rolled back. 'b' itself doesn't get a separate rollback call — XAException
        // XA_RBROLLBACK already finalised it on its side.
        assertThat(log).contains(
                "a.start", "b.start",
                "a.end(TMSUCCESS)", "b.end(TMSUCCESS)",
                "a.prepare", "b.prepare",
                "a.rollback");
    }

    @Test
    void rollbackTriggersXaRollbackOnEachEnlistedResource() throws Exception {
        List<String> log = new ArrayList<>();
        var a = new RecordingXAResource("a", log);
        var b = new RecordingXAResource("b", log);

        tm.begin();
        tm.getTransaction().enlistResource(a);
        tm.getTransaction().enlistResource(b);
        tm.rollback();

        assertThat(log).containsExactly(
                "a.start", "b.start",
                "a.end(TMFAIL)", "b.end(TMFAIL)",
                "a.rollback", "b.rollback");
    }

    @Test
    void delistResourceWithSuccessExitsTheResource() throws Exception {
        List<String> log = new ArrayList<>();
        var r = new RecordingXAResource("a", log);

        tm.begin();
        var tx = tm.getTransaction();
        tx.enlistResource(r);
        boolean delisted = tx.delistResource(r, XAResource.TMSUCCESS);
        assertThat(delisted).isTrue();

        // Delisting calls end(TMSUCCESS) right then. The commit later still finalises
        // (one-phase commit since only one resource).
        assertThat(log).containsExactly("a.start", "a.end(TMSUCCESS)");

        tm.commit();
        assertThat(log).containsExactly(
                "a.start", "a.end(TMSUCCESS)",
                "a.commit(onePhase=true)");
    }
}
