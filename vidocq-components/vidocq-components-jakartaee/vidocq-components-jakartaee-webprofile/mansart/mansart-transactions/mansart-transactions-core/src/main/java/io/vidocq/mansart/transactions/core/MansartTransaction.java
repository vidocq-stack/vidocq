package io.vidocq.mansart.transactions.core;

import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
// XAResource was NOT migrated to the jakarta namespace by the Jakarta EE 9 rename — it stays
// in the JDK's javax.transaction.xa package (module java.transaction.xa). Eclipse Foundation
// kept it as-is because the JTA TCK references it at the JDK level.
import javax.transaction.xa.XAResource;

/**
 * Single-thread, in-memory {@link Transaction}. Holds nothing but a status word for M1 — resource
 * enlistment, synchronisations and prepare/commit will land in M2/M4.
 *
 * <p>State machine accepted for M1 (subset of JTA §3.3.3) :
 * <pre>
 *   ACTIVE ──setRollbackOnly()──▶ MARKED_ROLLBACK
 *      │                                │
 *      │ commit()                       │ commit()
 *      ▼                                ▼
 *   COMMITTING                      ROLLING_BACK
 *      │                                │
 *      ▼                                ▼
 *   COMMITTED                       ROLLEDBACK
 *
 *   ACTIVE ──rollback()──▶ ROLLING_BACK ──▶ ROLLEDBACK
 *   MARKED_ROLLBACK ──rollback()──▶ ROLLING_BACK ──▶ ROLLEDBACK
 * </pre>
 *
 * <p>{@link #commit()} on {@code MARKED_ROLLBACK} performs the rollback then re-throws as a
 * {@link RollbackException} (per spec).
 */
final class MansartTransaction implements Transaction {

    private volatile int status = Status.STATUS_ACTIVE;

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public void commit() throws RollbackException, SystemException {
        if (status == Status.STATUS_MARKED_ROLLBACK) {
            doRollback();
            throw new RollbackException("Transaction was marked rollback-only");
        }
        if (status != Status.STATUS_ACTIVE) {
            throw new IllegalStateException(
                    "commit() requires STATUS_ACTIVE, was " + statusName(status));
        }
        status = Status.STATUS_COMMITTING;
        // M2 : beforeCompletion(s) on synchronisations
        // M4 : prepare + commit phases on enlisted XAResources
        status = Status.STATUS_COMMITTED;
        // M2 : afterCompletion(STATUS_COMMITTED) on synchronisations
    }

    @Override
    public void rollback() throws SystemException {
        if (status != Status.STATUS_ACTIVE && status != Status.STATUS_MARKED_ROLLBACK) {
            throw new IllegalStateException(
                    "rollback() requires STATUS_ACTIVE or STATUS_MARKED_ROLLBACK, was "
                            + statusName(status));
        }
        doRollback();
    }

    private void doRollback() {
        status = Status.STATUS_ROLLING_BACK;
        // M4 : rollback on enlisted XAResources
        status = Status.STATUS_ROLLEDBACK;
        // M2 : afterCompletion(STATUS_ROLLEDBACK) on synchronisations
    }

    @Override
    public void setRollbackOnly() {
        if (status != Status.STATUS_ACTIVE) {
            throw new IllegalStateException(
                    "setRollbackOnly() requires STATUS_ACTIVE, was " + statusName(status));
        }
        status = Status.STATUS_MARKED_ROLLBACK;
    }

    @Override
    public boolean enlistResource(XAResource xaRes) {
        throw new UnsupportedOperationException("M4 not implemented");
    }

    @Override
    public boolean delistResource(XAResource xaRes, int flag) {
        throw new UnsupportedOperationException("M4 not implemented");
    }

    @Override
    public void registerSynchronization(Synchronization sync) {
        throw new UnsupportedOperationException("M2 not implemented");
    }

    private static String statusName(int s) {
        return switch (s) {
            case Status.STATUS_ACTIVE          -> "STATUS_ACTIVE";
            case Status.STATUS_MARKED_ROLLBACK -> "STATUS_MARKED_ROLLBACK";
            case Status.STATUS_PREPARED        -> "STATUS_PREPARED";
            case Status.STATUS_COMMITTED       -> "STATUS_COMMITTED";
            case Status.STATUS_ROLLEDBACK      -> "STATUS_ROLLEDBACK";
            case Status.STATUS_UNKNOWN         -> "STATUS_UNKNOWN";
            case Status.STATUS_NO_TRANSACTION  -> "STATUS_NO_TRANSACTION";
            case Status.STATUS_PREPARING       -> "STATUS_PREPARING";
            case Status.STATUS_COMMITTING      -> "STATUS_COMMITTING";
            case Status.STATUS_ROLLING_BACK    -> "STATUS_ROLLING_BACK";
            default                            -> "UNKNOWN(" + s + ")";
        };
    }
}
