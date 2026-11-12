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
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a transaction owes its participants while it completes: a rollback-only mark set during
 * {@code beforeCompletion} is honoured, a participant may register another one from {@code beforeCompletion},
 * the mark may be set twice, and a commit that fails on one resource still reaches the others.
 */
class CompletionContractTest {

    private final TransactionManager tm = new MansartTransactionManager();
    private final List<String> log = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        if (tm.getStatus() != Status.STATUS_NO_TRANSACTION) {
            try { tm.rollback(); } catch (Exception _) { /* best effort */ }
        }
    }

    private static Synchronization before(Runnable action) {
        return new Synchronization() {
            @Override public void beforeCompletion() { action.run(); }
            @Override public void afterCompletion(int status) { /* nothing */ }
        };
    }

    @Test
    void aRollbackOnlyMarkSetInBeforeCompletionRollsBack() throws Exception {
        tm.begin();
        var resource = new RecordingXAResource("r", log);
        tm.getTransaction().enlistResource(resource);
        tm.getTransaction().registerSynchronization(before(() -> {
            try {
                tm.setRollbackOnly();
            } catch (SystemException e) {
                throw new IllegalStateException(e);
            }
        }));

        assertThatThrownBy(tm::commit).isInstanceOf(RollbackException.class);
        assertThat(log).contains("r.rollback").doesNotContain("r.commit(onePhase=true)");
    }

    @Test
    void aSynchronizationMayRegisterAnotherFromBeforeCompletion() throws Exception {
        tm.begin();
        var tx = tm.getTransaction();
        tx.registerSynchronization(before(() -> {
            try {
                tx.registerSynchronization(new Synchronization() {
                    @Override public void beforeCompletion() { log.add("late.before"); }
                    @Override public void afterCompletion(int status) { log.add("late.after(" + status + ")"); }
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));

        assertThatCode(tm::commit).doesNotThrowAnyException();
        assertThat(log).containsExactly("late.before", "late.after(" + Status.STATUS_COMMITTED + ")");
    }

    @Test
    void settingRollbackOnlyTwiceIsAllowed() throws Exception {
        tm.begin();
        tm.setRollbackOnly();

        assertThatCode(tm::setRollbackOnly).doesNotThrowAnyException();
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
    }

    @Test
    void aTwoPhaseCommitThatFailsOnOneResourceStillCommitsTheOthers() throws Exception {
        tm.begin();
        var first = new RecordingXAResource("first", log);
        var second = new RecordingXAResource("second", log);
        first.commitThrows = true;
        tm.getTransaction().enlistResource(first);
        tm.getTransaction().enlistResource(second);

        assertThatThrownBy(tm::commit).isInstanceOf(Exception.class);
        assertThat(log).contains("second.commit(onePhase=false)");
    }
}
