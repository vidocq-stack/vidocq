package io.vidocq.mansart.transactions.cdi;

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
 * <p>Interposed synchronisations are registered as plain {@link Transaction#registerSynchronization(Synchronization)}
 * for now — Jakarta Transactions §10.3 mandates a specific ordering (interposed run AFTER normal
 * syncs in {@code beforeCompletion}, BEFORE in {@code afterCompletion}) which {@link MansartTransaction}
 * does not yet differentiate ; see {@code BUG.md}/M8 for the follow-up.
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
        } catch (SystemException e) {
            return null;
        }
    }

    @Override
    public void putResource(Object key, Object value) {
        Objects.requireNonNull(key, "TSR resource key must not be null");
        Transaction tx = requireActiveTx();
        ConcurrentMap<Object, Object> map = perTxResources.computeIfAbsent(tx, t -> {
            try {
                t.registerSynchronization(new Synchronization() {
                    @Override public void beforeCompletion() { /* no-op */ }
                    @Override public void afterCompletion(int status) { perTxResources.remove(t); }
                });
            } catch (Exception ignored) {
                // The TX may already be committing/rolling back — accept the leak ; the entry
                // becomes weakly reachable when the Transaction is garbage-collected.
            }
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
        Transaction tx = requireActiveTx();
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
        } catch (SystemException e) {
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
