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

import jakarta.transaction.InvalidTransactionException;
import jakarta.transaction.Status;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3 — {@link TransactionManager#suspend()} / {@link TransactionManager#resume(Transaction)}.
 * Spec contracts :
 * <ul>
 *   <li>{@code suspend()} on no active TX returns {@code null} and leaves the thread untouched.</li>
 *   <li>{@code suspend()} on an active TX returns it and clears the thread's binding.</li>
 *   <li>{@code resume(null)} is a no-op (allowed by spec convention) ; some impls throw, the
 *       Mansart contract here is "no-op" for symmetry with {@code suspend()} on empty.</li>
 *   <li>{@code resume(tx)} when a TX is already active raises {@link IllegalStateException}.</li>
 *   <li>{@code resume(tx)} of a foreign / unknown {@link Transaction} raises
 *       {@link InvalidTransactionException}.</li>
 * </ul>
 */
class SuspendResumeTest {

    private final TransactionManager tm = new MansartTransactionManager();

    @AfterEach
    void cleanup() throws Exception {
        if (tm.getStatus() != Status.STATUS_NO_TRANSACTION) {
            try { tm.rollback(); } catch (Exception _) { /* best effort */ }
        }
    }

    @Test
    void suspendWhenNoTxReturnsNull() throws Exception {
        assertThat(tm.suspend()).isNull();
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void suspendThenResumeIsTransparent() throws Exception {
        tm.begin();
        Transaction tx = tm.suspend();
        assertThat(tx).isNotNull();
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);

        tm.resume(tx);
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
        assertThat(tm.getTransaction()).isSameAs(tx);
        tm.rollback();
    }

    @Test
    void resumeWhileAlreadyActiveFails() throws Exception {
        tm.begin();
        Transaction tx1 = tm.suspend();
        tm.begin();
        // Now tx2 is active. Resuming tx1 must fail until tx2 is suspended/finished.
        assertThatThrownBy(() -> tm.resume(tx1)).isInstanceOf(IllegalStateException.class);
        tm.rollback();
        // tx1 must still be valid — resume after the second TX ended.
        tm.resume(tx1);
        tm.rollback();
    }

    @Test
    void resumeNullIsNoOp() throws Exception {
        tm.resume(null);
        assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    }

    @Test
    void resumeForeignTransactionFails() {
        Transaction stranger = new Transaction() {
            @Override public void commit() { }
            @Override public boolean delistResource(javax.transaction.xa.XAResource x, int f) { return false; }
            @Override public boolean enlistResource(javax.transaction.xa.XAResource x) { return false; }
            @Override public int getStatus() { return Status.STATUS_ACTIVE; }
            @Override public void registerSynchronization(jakarta.transaction.Synchronization s) { }
            @Override public void rollback() { }
            @Override public void setRollbackOnly() { }
        };
        assertThatThrownBy(() -> tm.resume(stranger)).isInstanceOf(InvalidTransactionException.class);
    }

    @Test
    void txObjectStillUsableAfterSuspend() throws Exception {
        tm.begin();
        Transaction tx = tm.suspend();
        // Even unbound from the thread, the tx object can be queried.
        assertThat(tx.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
        tm.resume(tx);
        tm.rollback();
    }

    @Test
    void doubleSuspendReturnsSecondNull() throws Exception {
        tm.begin();
        Transaction tx = tm.suspend();
        assertThat(tx).isNotNull();
        assertThat(tm.suspend()).isNull();
        tm.resume(tx);
        tm.rollback();
    }
}
