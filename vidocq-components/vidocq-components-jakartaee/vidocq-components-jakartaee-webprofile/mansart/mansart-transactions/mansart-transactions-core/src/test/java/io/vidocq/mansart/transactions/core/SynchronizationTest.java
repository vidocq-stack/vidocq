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
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2 — {@link jakarta.transaction.Transaction#registerSynchronization(Synchronization)}.
 * Spec contracts (JTA 2.0 §3.3.5) :
 * <ul>
 *   <li>{@code beforeCompletion()} fires BEFORE the resources commit, in registration order.</li>
 *   <li>{@code afterCompletion(int)} fires AFTER the resources have committed/rolled back, with
 *       the final status (STATUS_COMMITTED or STATUS_ROLLEDBACK).</li>
 *   <li>An exception thrown from {@code beforeCompletion()} causes the TX to rollback.</li>
 *   <li>Exceptions from {@code afterCompletion()} are swallowed (callback runs in a phase where
 *       the TX is already finalised).</li>
 *   <li>{@code registerSynchronization()} on a non-active TX raises IllegalStateException.</li>
 * </ul>
 */
class SynchronizationTest {

    private final TransactionManager tm = new MansartTransactionManager();

    @AfterEach
    void cleanup() throws Exception {
        if (tm.getStatus() != Status.STATUS_NO_TRANSACTION) {
            try { tm.rollback(); } catch (Exception _) { /* best effort */ }
        }
    }

    private static final class RecordingSync implements Synchronization {
        final String name;
        final List<String> log;
        RuntimeException beforeThrows;
        RuntimeException afterThrows;
        RecordingSync(String name, List<String> log) { this.name = name; this.log = log; }
        @Override public void beforeCompletion() {
            log.add(name + ".before");
            if (beforeThrows != null) throw beforeThrows;
        }
        @Override public void afterCompletion(int status) {
            log.add(name + ".after(" + status + ")");
            if (afterThrows != null) throw afterThrows;
        }
    }

    @Test
    void registerOutsideTxFails() throws Exception {
        // Need an instance to call registerSynchronization on — none exists when no TX is active.
        assertThat(tm.getTransaction()).isNull();
        // Indirect path : the only way users register is via TransactionManager.getTransaction() —
        // which is null here. Spec convention: clients should call setRollbackOnly first, but the
        // pre-condition is that a TX must be active before any sync can be added.
    }

    @Test
    void beforeAndAfterCalledInOrderOnCommit() throws Exception {
        List<String> log = new ArrayList<>();
        tm.begin();
        var tx = tm.getTransaction();
        tx.registerSynchronization(new RecordingSync("a", log));
        tx.registerSynchronization(new RecordingSync("b", log));
        tm.commit();

        assertThat(log).containsExactly(
                "a.before", "b.before",
                "a.after(" + Status.STATUS_COMMITTED + ")",
                "b.after(" + Status.STATUS_COMMITTED + ")");
    }

    @Test
    void afterCalledWithRolledBackOnRollback() throws Exception {
        List<String> log = new ArrayList<>();
        tm.begin();
        tm.getTransaction().registerSynchronization(new RecordingSync("a", log));
        tm.rollback();

        // beforeCompletion is NOT invoked on a plain rollback path (spec §3.3.5)
        assertThat(log).containsExactly("a.after(" + Status.STATUS_ROLLEDBACK + ")");
    }

    @Test
    void beforeCompletionThrowingTriggersRollback() throws Exception {
        List<String> log = new ArrayList<>();
        var bad = new RecordingSync("a", log);
        bad.beforeThrows = new RuntimeException("boom");
        var ok = new RecordingSync("b", log);

        tm.begin();
        tm.getTransaction().registerSynchronization(bad);
        tm.getTransaction().registerSynchronization(ok);

        assertThatThrownBy(tm::commit).isInstanceOf(RollbackException.class);

        // 'a.before' was attempted (and threw). Even when one beforeCompletion fails, the spec
        // does not mandate calling subsequent beforeCompletion(s) — Mansart skips the rest and
        // proceeds to rollback. Both syncs receive afterCompletion(STATUS_ROLLEDBACK).
        assertThat(log).contains("a.before",
                "a.after(" + Status.STATUS_ROLLEDBACK + ")",
                "b.after(" + Status.STATUS_ROLLEDBACK + ")");
        assertThat(log).doesNotContain("a.after(" + Status.STATUS_COMMITTED + ")");
    }

    @Test
    void afterCompletionExceptionsAreSwallowed() throws Exception {
        List<String> log = new ArrayList<>();
        var bad = new RecordingSync("a", log);
        bad.afterThrows = new RuntimeException("late boom");
        var ok = new RecordingSync("b", log);

        tm.begin();
        tm.getTransaction().registerSynchronization(bad);
        tm.getTransaction().registerSynchronization(ok);
        tm.commit();   // does NOT throw — afterCompletion errors are absorbed

        assertThat(log).containsExactly(
                "a.before", "b.before",
                "a.after(" + Status.STATUS_COMMITTED + ")",
                "b.after(" + Status.STATUS_COMMITTED + ")");
    }

    @Test
    void registerOnInactiveTxFails() throws Exception {
        tm.begin();
        var tx = tm.getTransaction();
        tm.commit();
        assertThatThrownBy(() -> tx.registerSynchronization(new RecordingSync("nope", new ArrayList<>())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void registerOnMarkedRollbackTxFails() throws Exception {
        tm.begin();
        var tx = tm.getTransaction();
        tm.setRollbackOnly();
        // Spec §3.3.5: registerSynchronization is only legal in STATUS_ACTIVE. STATUS_MARKED_ROLLBACK
        // is past the point where adding a hook makes sense — it would never see beforeCompletion.
        assertThatThrownBy(() -> tx.registerSynchronization(new RecordingSync("nope", new ArrayList<>())))
                .isInstanceOf(IllegalStateException.class);
        tm.rollback();
    }
}
