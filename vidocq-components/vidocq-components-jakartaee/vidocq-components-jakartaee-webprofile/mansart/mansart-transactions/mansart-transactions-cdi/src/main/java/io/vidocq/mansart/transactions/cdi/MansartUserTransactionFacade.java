package io.vidocq.mansart.transactions.cdi;

import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.UserTransaction;

/**
 * Thin {@link UserTransaction} façade over a {@link TransactionManager} — same delegation
 * pattern as the TCK adapter, kept in this package to avoid leaking a runtime dep on the
 * tck module.
 */
final class MansartUserTransactionFacade implements UserTransaction {

    private final TransactionManager tm;

    MansartUserTransactionFacade(TransactionManager tm) {
        this.tm = tm;
    }

    @Override public void begin() throws NotSupportedException, SystemException { tm.begin(); }

    @Override public void commit()
            throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
                   SecurityException, IllegalStateException, SystemException {
        tm.commit();
    }

    @Override public void rollback() throws IllegalStateException, SecurityException, SystemException {
        tm.rollback();
    }

    @Override public void setRollbackOnly() throws IllegalStateException, SystemException {
        tm.setRollbackOnly();
    }

    @Override public int getStatus() throws SystemException { return tm.getStatus(); }

    @Override public void setTransactionTimeout(int seconds) throws SystemException {
        tm.setTransactionTimeout(seconds);
    }
}
