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

import java.util.List;

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
    private final RecoveryLog recoveryLog;

    /** Default timeout in seconds applied to new transactions. {@code 0} = no timeout. M1 stores
     *  the value but does not enforce it yet — enforcement lands with M2's reaper task. */
    private volatile int defaultTimeoutSeconds;

    /** Volatile TM (no recovery log) — the most common case for embedded apps and tests. */
    public MansartTransactionManager() {
        this(NoOpRecoveryLog.INSTANCE);
    }

    /** Durable TM — pass a {@link FileRecoveryLog} (or any custom impl) to enable in-doubt
     *  transaction detection on restart. */
    public MansartTransactionManager(RecoveryLog recoveryLog) {
        this.recoveryLog = recoveryLog;
    }

    @Override
    public void begin() throws NotSupportedException {
        if (active.get() != null) {
            throw new NotSupportedException(
                    "A transaction is already active on this thread — nested transactions "
                            + "are not supported (use suspend()/resume() in M3+)");
        }
        active.set(new MansartTransaction(recoveryLog));
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

    /**
     * Replays the {@link RecoveryLog} and returns the records that are in-doubt — i.e. whose
     * most recent record for a given Xid is not {@code COMPLETED}.
     *
     * <p>Caller-side handling :
     * <ul>
     *   <li>{@link RecoveryLog.Type#PREPARED} → the resource voted yes but no commit decision
     *       was ever durably written ; safe to roll back the branch via the driver.</li>
     *   <li>{@link RecoveryLog.Type#COMMITTING} → point of no return was crossed ; the branch
     *       must be committed via the driver to restore consistency.</li>
     * </ul>
     *
     * <p>For M5 we surface the records and let the caller decide ; M5b will add a high-level
     * {@code recover(XAResource[])} that auto-resolves against driver-side {@code XAResource.recover()}
     * results.
     */
    public List<RecoveryLog.Record> recover() throws java.io.IOException {
        var lastByXid = new java.util.LinkedHashMap<XidKey, RecoveryLog.Record>();
        for (var rec : recoveryLog.scan()) {
            var key = XidKey.of(rec.xid());
            if (rec.type() == RecoveryLog.Type.COMPLETED) {
                lastByXid.remove(key);
            } else {
                lastByXid.put(key, rec);
            }
        }
        return List.copyOf(lastByXid.values());
    }

    /** Equality wrapper for {@link javax.transaction.xa.Xid} — by-content hash/equals on
     *  format/gtrid/bqual. Required because Xid is an interface with no contract for equals. */
    private record XidKey(int format, java.util.List<Byte> gtrid, java.util.List<Byte> bqual) {
        static XidKey of(javax.transaction.xa.Xid x) {
            var g = x.getGlobalTransactionId();
            var b = x.getBranchQualifier();
            var gl = new java.util.ArrayList<Byte>(g.length);
            for (byte v : g) gl.add(v);
            var bl = new java.util.ArrayList<Byte>(b.length);
            for (byte v : b) bl.add(v);
            return new XidKey(x.getFormatId(), gl, bl);
        }
    }
}
