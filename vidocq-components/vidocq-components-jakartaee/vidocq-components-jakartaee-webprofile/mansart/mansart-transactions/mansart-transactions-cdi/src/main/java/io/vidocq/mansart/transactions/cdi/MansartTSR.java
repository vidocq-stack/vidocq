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
package io.vidocq.mansart.transactions.cdi;

import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link TransactionSynchronizationRegistry} implementation that stores per-transaction resources
 * and interposed synchronisations on the side of the {@link MansartTransactionManager}.
 *
 * <p>Resources are kept in a {@link ConcurrentMap} keyed by the {@link Transaction} instance ;
 * an auto-registered cleanup {@link Synchronization} removes the per-tx map at completion so
 * we never leak across runs.
 *
 * <p>Interposed synchronisations go to {@link MansartTransactionManager#registerInterposedSynchronization}: they
 * run after the application's in {@code beforeCompletion}, before them in {@code afterCompletion}, and may join a
 * transaction marked rollback-only (MANSART-063).
 */
final class MansartTSR implements TransactionSynchronizationRegistry {

    private final TransactionManager tm;
    private final ConcurrentMap<Transaction, ConcurrentMap<Object, Object>> perTxResources =
            new ConcurrentHashMap<>();

    MansartTSR(TransactionManager tm) {
        this.tm = tm;
    }

    @Override
    public Object getTransactionKey() {
        try {
            return tm.getTransaction();
        } catch (SystemException _) {
            return null;
        }
    }

    @Override
    public void putResource(Object key, Object value) {
        Objects.requireNonNull(key, "TSR resource key must not be null");
        Transaction tx = requireActiveTx();
        ConcurrentMap<Object, Object> map = perTxResources.computeIfAbsent(tx, t -> {
            // Interposed: a transaction marked rollback-only takes it too, so its resources are
            // always let go (MANSART-063).
            interpose(t, new Synchronization() {
                @Override public void beforeCompletion() { /* no-op */ }
                @Override public void afterCompletion(int status) { perTxResources.remove(t); }
            });
            return new ConcurrentHashMap<>();
        });
        map.put(key, value);
    }

    @Override
    public Object getResource(Object key) {
        Objects.requireNonNull(key, "TSR resource key must not be null");
        Transaction tx = requireActiveTx();
        ConcurrentMap<Object, Object> map = perTxResources.get(tx);
        return map == null ? null : map.get(key);
    }

    @Override
    public void registerInterposedSynchronization(Synchronization sync) {
        interpose(requireActiveTx(), sync);
    }

    /** After the application's synchronizations before completion, ahead of them after it. */
    private void interpose(Transaction tx, Synchronization sync) {
        if (tm instanceof MansartTransactionManager own) {
            own.registerInterposedSynchronization(tx, sync);
            return;
        }
        try {
            tx.registerSynchronization(sync);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot register interposed synchronization", e);
        }
    }

    @Override
    public int getTransactionStatus() {
        try {
            return tm.getStatus();
        } catch (SystemException _) {
            return Status.STATUS_NO_TRANSACTION;
        }
    }

    @Override
    public void setRollbackOnly() {
        try {
            requireActiveTx().setRollbackOnly();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean getRollbackOnly() {
        try {
            return requireActiveTx().getStatus() == Status.STATUS_MARKED_ROLLBACK;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Transaction requireActiveTx() {
        try {
            Transaction tx = tm.getTransaction();
            if (tx == null) {
                throw new IllegalStateException(
                        "TransactionSynchronizationRegistry called outside a transaction");
            }
            return tx;
        } catch (SystemException e) {
            throw new IllegalStateException(e);
        }
    }
}
