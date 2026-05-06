package io.vidocq.mansart.transactions.core;

import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.InvalidTransactionException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

/**
 * Local-only Jakarta Transactions 2.0 {@link TransactionManager}.
 *
 * <p>M1 covers single-thread {@code begin/commit/rollback} only — synchronisations (M2),
 * suspend/resume (M3), resource enrolment + 2PC (M4) and recovery (M5) come in subsequent commits.
 *
 * <h3>Why {@link ThreadLocal} and not {@link java.lang.ScopedValue}</h3>
 * The Jakarta Transactions API is imperative : {@code begin()} returns and {@code commit()} comes
 * later from arbitrary call sites. {@link java.lang.ScopedValue} requires an enclosing
 * {@code run(...)} block, which would force every consumer to wrap its workload in a callback —
 * incompatible with the spec. {@link ThreadLocal} is correct here ; virtual threads inherit it
 * just like platform threads. M3 may add a {@code ScopedValue}-bound {@code runInTransaction(...)}
 * convenience helper on top of this, but the core is and stays imperative.
 */
public class MansartTransactionManager implements TransactionManager {

    private final ThreadLocal<MansartTransaction> active = new ThreadLocal<>();

    /** Default timeout in seconds applied to new transactions. {@code 0} = no timeout. M1 stores
     *  the value but does not enforce it yet — enforcement lands with M2's reaper task. */
    private volatile int defaultTimeoutSeconds;

    @Override
    public void begin() throws NotSupportedException {
        if (active.get() != null) {
            throw new NotSupportedException(
                    "A transaction is already active on this thread — nested transactions "
                            + "are not supported (use suspend()/resume() in M3+)");
        }
        active.set(new MansartTransaction());
    }

    @Override
    public void commit() throws RollbackException, HeuristicMixedException,
            HeuristicRollbackException, SecurityException, SystemException {
        MansartTransaction tx = currentRequired();
        try {
            tx.commit();
        } finally {
            active.remove();
        }
    }

    @Override
    public void rollback() throws SecurityException, SystemException {
        MansartTransaction tx = currentRequired();
        try {
            tx.rollback();
        } finally {
            active.remove();
        }
    }

    @Override
    public int getStatus() {
        MansartTransaction tx = active.get();
        return tx == null ? Status.STATUS_NO_TRANSACTION : tx.getStatus();
    }

    @Override
    public Transaction getTransaction() {
        return active.get();
    }

    @Override
    public void setRollbackOnly() throws SystemException {
        currentRequired().setRollbackOnly();
    }

    @Override
    public void setTransactionTimeout(int seconds) throws SystemException {
        if (seconds < 0) {
            throw new SystemException("Negative transaction timeout: " + seconds);
        }
        this.defaultTimeoutSeconds = seconds;
    }

    @Override
    public void resume(Transaction tobj)
            throws InvalidTransactionException, IllegalStateException, SystemException {
        if (tobj == null) {
            // Spec is permissive — we treat null as "do nothing", symmetric with suspend()
            // returning null when no TX is bound.
            return;
        }
        if (active.get() != null) {
            throw new IllegalStateException(
                    "Cannot resume — a transaction is already active on this thread");
        }
        if (!(tobj instanceof MansartTransaction mtx)) {
            throw new InvalidTransactionException(
                    "Foreign Transaction implementation: " + tobj.getClass().getName());
        }
        active.set(mtx);
    }

    @Override
    public Transaction suspend() throws SystemException {
        MansartTransaction tx = active.get();
        if (tx == null) {
            return null;
        }
        active.remove();
        return tx;
    }

    private MansartTransaction currentRequired() {
        MansartTransaction tx = active.get();
        if (tx == null) {
            throw new IllegalStateException("No transaction active on this thread");
        }
        return tx;
    }
}
