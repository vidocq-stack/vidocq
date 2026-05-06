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
 * Virtual-thread-native implementation of {@link TransactionManager} — single-resource (1PC) only
 * for now. Each thread sees a transaction context bound by {@link java.lang.ScopedValue} (set in
 * {@link #begin()}, cleared by {@link #commit()}/{@link #rollback()}).
 *
 * <p>Roadmap, in order: M1 begin/commit/rollback + STATUS_ACTIVE / STATUS_NO_TRANSACTION,
 * M2 Synchronization callbacks (before/after), M3 suspend/resume,
 * M4 multi-resource enrolment with prepare/commit/rollback (2PC), M5 recovery log,
 * M6 official Jakarta Transactions 2.0 TCK.
 *
 * <p>This is currently a TDD skeleton — every method throws {@link UnsupportedOperationException}.
 * The first test {@code TransactionManagerSmokeTest} should be the next thing written.
 */
public class MansartTransactionManager implements TransactionManager {

    @Override
    public void begin() throws NotSupportedException, SystemException {
        throw new UnsupportedOperationException("M1 not implemented");
    }

    @Override
    public void commit()
            throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
            SecurityException, IllegalStateException, SystemException {
        throw new UnsupportedOperationException("M1 not implemented");
    }

    @Override
    public void rollback() throws IllegalStateException, SecurityException, SystemException {
        throw new UnsupportedOperationException("M1 not implemented");
    }

    @Override
    public int getStatus() throws SystemException {
        return Status.STATUS_NO_TRANSACTION;
    }

    @Override
    public Transaction getTransaction() throws SystemException {
        return null;
    }

    @Override
    public void setRollbackOnly() throws IllegalStateException, SystemException {
        throw new UnsupportedOperationException("M1 not implemented");
    }

    @Override
    public void setTransactionTimeout(int seconds) throws SystemException {
        throw new UnsupportedOperationException("M1 not implemented");
    }

    @Override
    public void resume(Transaction tobj)
            throws InvalidTransactionException, IllegalStateException, SystemException {
        throw new UnsupportedOperationException("M3 not implemented");
    }

    @Override
    public Transaction suspend() throws SystemException {
        throw new UnsupportedOperationException("M3 not implemented");
    }
}
