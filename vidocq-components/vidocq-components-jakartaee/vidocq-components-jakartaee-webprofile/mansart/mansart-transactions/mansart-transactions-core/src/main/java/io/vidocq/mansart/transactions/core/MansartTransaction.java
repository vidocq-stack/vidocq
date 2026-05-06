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

import java.util.ArrayList;
import java.util.List;

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
    private final List<Synchronization> syncs = new ArrayList<>();

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
        // beforeCompletion runs while we are still STATUS_ACTIVE so write-flushes can use the
        // resource as if user code were still executing. Any thrown exception triggers rollback.
        try {
            invokeBeforeCompletion();
        } catch (RuntimeException ex) {
            doRollback();
            RollbackException rex = new RollbackException(
                    "Synchronization.beforeCompletion failed — transaction rolled back");
            rex.initCause(ex);
            throw rex;
        }
        status = Status.STATUS_COMMITTING;
        // M4 : prepare + commit phases on enlisted XAResources
        status = Status.STATUS_COMMITTED;
        invokeAfterCompletion(Status.STATUS_COMMITTED);
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
        invokeAfterCompletion(Status.STATUS_ROLLEDBACK);
    }

    private void invokeBeforeCompletion() {
        for (Synchronization s : syncs) {
            s.beforeCompletion();
        }
    }

    /**
     * After-completion callbacks run when the transaction is finalised — by spec they should
     * never propagate an exception. We swallow each one so that one failing sync can't stop the
     * others from observing the outcome.
     */
    private void invokeAfterCompletion(int finalStatus) {
        for (Synchronization s : syncs) {
            try {
                s.afterCompletion(finalStatus);
            } catch (RuntimeException ignored) {
                // spec §3.3.5 — afterCompletion exceptions are swallowed
            }
        }
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
        if (status != Status.STATUS_ACTIVE) {
            throw new IllegalStateException(
                    "registerSynchronization() requires STATUS_ACTIVE, was " + statusName(status));
        }
        syncs.add(sync);
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
